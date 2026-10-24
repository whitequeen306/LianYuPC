#!/usr/bin/env python3
"""Pull latest main on cloud server and rebuild asr + backend + api-gateway."""
import os
import sys
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 900, quiet: bool = False) -> str:
    """执行远端命令；quiet=True 时不回显命令与输出（用于纯取值的探测）。"""
    if not quiet:
        print(f"$ {cmd}", flush=True)
        # 不用 PTY：compose 的进度 TUI 在 PTY 下可能永不返回 EOF（2026-10 两次假死：
    # 服务器侧构建早已结束，客户端却一直读不到结束符，整条发版流水线卡在 deploy）。
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=False)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip() and not quiet:
        text = out[-5000:]
        sys.stdout.buffer.write(text.encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
    if code != 0:
        if err.strip():
            sys.stderr.buffer.write(err[-2000:].encode("utf-8", errors="replace"))
            sys.stderr.buffer.write(b"\n")
        raise SystemExit(code)
    return out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env or environment", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    # ── 0) 关键配置自检：桌面能力层的内网通道 token 必须存在于云端 .env ────────────
    # 本脚本只 pull + rebuild，**不写** /opt/lianyu/.env（那是运维资产）。
    # 但 Python↔Java 的内部通道在缺 token 时是 fail-closed 的：所有 desk_* 会直接报
    # channel_not_configured / invalid_signature，用户看起来就是「角色不会动手」，
    # 而且完全不会报错到部署日志里。所以这里提前拦，别让它悄悄上线。
    token_check = run(
        client,
        "grep -cE '^LIANYU_AGENT_INTERNAL_TOKEN=.+' /opt/lianyu/.env 2>/dev/null || true",
        timeout=60,
        quiet=True,
    ).strip()
    if token_check not in ("1",):
        print(
            "\n[deploy] /opt/lianyu/.env 缺少 LIANYU_AGENT_INTERNAL_TOKEN（或为空）。\n"
            "  桌面能力层会 fail-closed：角色无法操控电脑，但不会报错到部署日志。\n"
            "  补法（两端同值，随机 64 hex）：\n"
            "    ssh root@" + HOST + " \"grep -q '^LIANYU_AGENT_INTERNAL_TOKEN=' /opt/lianyu/.env"
            " || echo 'LIANYU_AGENT_INTERNAL_TOKEN=<64位hex>' >> /opt/lianyu/.env\"\n"
            "  再 `docker compose up -d --no-build backend agent agent2`。\n",
            file=sys.stderr,
        )
        client.close()
        raise SystemExit(57)

    run(client, "cd /opt/lianyu && git pull origin main")
    # 重建清单（逐层构建，且**必须 build 与 up 分开**）：
    #   1. agent / agent2 / agent-lb —— Python 运行时与 LB（LB 需 proxy_next_upstream 修复）
    #   2. backend —— Java（等 agent 层 healthy 后再起，避免启动期探测失败）
    #   3. asr / api-gateway —— 基建层
    #
    # 为什么不用 `up -d --build <svc>`：在本机验证过两次（backend、api-gateway），
    # buildkit 构建完成后 **compose 进程不退出**（`up --build` 卡住 15min+ 不返回），
    # 于是整条发版流水线挂在那里、后面 Electron 段永远不开始。
    # 拆成 build + `up --no-build` 后行为稳定。
    run(client, "cd /opt/lianyu && docker compose build agent agent2 agent-lb", timeout=1800)
    run(client, "cd /opt/lianyu && docker compose up -d --no-build agent agent2 agent-lb", timeout=600)
    run(client, "cd /opt/lianyu && docker compose build backend", timeout=1800)
    run(client, "cd /opt/lianyu && docker compose up -d --no-build backend", timeout=600)
    run(client, "cd /opt/lianyu && docker compose build asr api-gateway", timeout=1800)
    run(client, "cd /opt/lianyu && docker compose up -d --no-build asr api-gateway", timeout=600)
    run(
        client,
        "cd /opt/lianyu && set -a && . ./.env && set +a && "
        'docker exec lianyu-redis sh -c '
        '"redis-cli -a \\"${REDIS_PASSWORD}\\" --scan --pattern \'lianyu:user:output_lang:*\' '
        '| xargs -r redis-cli -a \\"${REDIS_PASSWORD}\\" del"',
    )
    import time
    # 双保险：backend captcha 可用 + agent LB 健康检查通过
    for attempt in range(12):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w 'captcha=%{http_code}' http://127.0.0.1:8080/api/auth/captcha; "
            "curl -s -o /dev/null -w ' agent=%{http_code}' http://127.0.0.1:18001/agent/health",
            timeout=30,
        )
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"$ health attempt {attempt + 1}: {out}")
        if "captcha=200" in out and "agent=200" in out:
            break
        if attempt < 11:
            time.sleep(15)
    else:
        raise SystemExit(56)
    # OFF 退役自检：所有 agent 端口必须接线（缺端口 = 该链路会直接报错）
    run(client, "docker logs lianyu-backend 2>&1 | grep -E 'all ports wired|ports missing' | tail -2")

    # 能力层自检（2026-10 起）：内部端点必须「无签名即拒」。
    # 这一条能同时证明三件事：新后端镜像已生效、签名校验在工作、缺密钥时 fail-closed。
    unsigned = run(
        client,
        "curl -s -X POST http://127.0.0.1:8080/internal/agent/desktop/exec "
        "-H 'Content-Type: application/json' -d '{}' | head -c 200",
        quiet=True,
    ).strip()
    print(f"$ internal exec (unsigned) -> {unsigned}")
    if "invalid_signature" not in unsigned:
        print(
            "[deploy] 警告：/internal/agent/desktop/exec 未按预期拒绝未签名请求；"
            "后端可能仍是旧镜像，或鉴权被改坏。",
            file=sys.stderr,
        )

    # Python 侧能力清单：desk_* 必须存在（否则客户端拿不到桌面能力）
    caps = run(client, "curl -s http://127.0.0.1:18001/agent/capabilities", quiet=True).strip()
    desk_count = caps.count('"desk_')
    print(f"$ agent capabilities: desk_* 提及 {desk_count} 次（应 ≥ 14）")
    if desk_count < 14:
        print("[deploy] 警告：能力清单里 desk_* 数量异常，检查 backend-agent 是否拉到新代码。", file=sys.stderr)

    run(client, "docker ps --format 'table {{.Names}}\\t{{.Status}}' | head -12")

    client.close()
    print("DEPLOY_DONE")


if __name__ == "__main__":
    main()
