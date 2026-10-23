"""能力策略（Python 侧预过滤）。

**它不是权威判定**：真正的执行闸门在 Electron 主进程（`policy.js`，纯函数 + 单测），
因为只有它知道当前前台窗口、敏感进程与用户的确认结果。
Python 这一层只做两件模型侧能做的事：

1. **提前拒绝**明知不允许的调用（省一次往返、也让模型尽快换路）；
2. **来源护栏**：`evidence == "screen_content"` 的 R3 高危动作一律不执行 ——
   屏幕上的文字是数据不是指令，这条把「提示注入成功」的后果压到 R0–R2。

判定顺序固定为「先拒后允」：denylist → 角色 max_danger → 敏感进程 → R3 词表。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.tool_registry import DangerLevel, ToolSpec

logger = logging.getLogger(__name__)

POLICY_PATH = Path(__file__).resolve().parent.parent / "prompts" / "capability_policy.json"

EVIDENCE_USER = "user_request"
EVIDENCE_SCREEN = "screen_content"
EVIDENCE_VALUES = (EVIDENCE_USER, EVIDENCE_SCREEN)

_RISK_ORDER: dict[str, int] = {"R0": 0, "R1": 1, "R2": 2, "R3": 3}

# 命中即视为「不可逆/涉钱/涉隐私」的控件文案（大小写不敏感）
_HIGH_RISK_LABEL_WORDS = (
    "删除", "移除", "卸载", "格式化", "清空", "覆盖",
    "支付", "付款", "转账", "购买", "下单", "确认支付",
    "发送", "提交", "发布", "注销", "解绑", "授权",
)

# 前台是这些进程时，键入/按键等同于执行命令 → 升 R3
_TERMINAL_PROCESSES = (
    "cmd.exe", "powershell.exe", "pwsh.exe", "windowsterminal.exe",
    "wt.exe", "conhost.exe", "bash.exe", "wsl.exe", "mintty.exe",
)

# 这些前台进程一律不碰（不截图、不注入）
_SENSITIVE_PROCESS_DEFAULTS = (
    "1password.exe", "keepass.exe", "keepassxc.exe", "bitwarden.exe",
    "lastpass.exe", "logonui.exe", "consent.exe", "credentialuibroker.exe",
    "bank", "支付宝", "微信支付",
)

DEFAULT_POLICY: dict[str, Any] = {
    "version": 1,
    # 默认角色的上限是 R3（不可逆动作**允许**，但必须由用户当面确认）；
    # 具体角色可在 `roles` 里收紧（如陪伴型角色只给 R1/R2）。
    # 这条选择的产品理由：确认弹窗是给人看的最后一道闸门；
    # 若默认直接禁掉 R3，那道闸门就永远用不到，用户说「帮我删掉这个」只会得到拒绝。
    "default": {"maxDanger": "R3"},
    "roles": {},
    "appAllowlist": [
        "notepad.exe", "explorer.exe", "chrome.exe", "msedge.exe", "firefox.exe",
        "wechat.exe", "weixin.exe", "cloudmusic.exe", "qqmusic.exe", "potplayermini64.exe",
        "code.exe", "winword.exe", "excel.exe", "powerpnt.exe",
    ],
    "appDenylist": [
        "mstsc.exe", "regedit.exe", "diskmgmt.msc", "gpedit.msc", "mmc.exe",
        "cmd.exe", "powershell.exe", "pwsh.exe", "wt.exe",
    ],
    "sensitiveProcesses": list(_SENSITIVE_PROCESS_DEFAULTS),
}


@dataclass(frozen=True)
class Decision:
    """一次调用的策略结论。"""

    allowed: bool
    needs_confirm: bool
    danger: DangerLevel
    reason: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "allowed": self.allowed,
            "needsConfirm": self.needs_confirm,
            "danger": self.danger,
            "reason": self.reason,
        }


def load_policy(path: Path | None = None) -> dict[str, Any]:
    """读策略文件；缺失/损坏一律回落到内置默认（fail-safe 而非 fail-open）。"""
    target = path or POLICY_PATH
    try:
        raw = json.loads(target.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return dict(DEFAULT_POLICY)
    except (OSError, ValueError) as exc:  # 坏 JSON 不能导致启动失败
        logger.warning("capability policy unreadable (%s), falling back to defaults", exc)
        return dict(DEFAULT_POLICY)
    if not isinstance(raw, dict):
        return dict(DEFAULT_POLICY)
    merged = dict(DEFAULT_POLICY)
    merged.update(raw)
    return merged


def _matches_any(needle: str, patterns: tuple[str, ...] | list[str]) -> bool:
    text = (needle or "").lower()
    if not text:
        return False
    return any(p and (p.lower() in text or text in p.lower()) for p in patterns)


def is_terminal_process(process_name: str | None) -> bool:
    return _matches_any(process_name or "", _TERMINAL_PROCESSES)


def is_sensitive_process(process_name: str | None, policy: dict[str, Any] | None = None) -> bool:
    patterns = (policy or DEFAULT_POLICY).get("sensitiveProcesses") or ()
    return _matches_any(process_name or "", tuple(patterns))


def label_hits_high_risk(label: str | None) -> bool:
    return _matches_any(label or "", _HIGH_RISK_LABEL_WORDS)


def _role_max_danger(policy: dict[str, Any], role: str | None) -> str:
    roles = policy.get("roles") or {}
    entry = roles.get(role or "default")
    if isinstance(entry, dict) and entry.get("maxDanger"):
        return str(entry["maxDanger"])
    default = policy.get("default") or {}
    return str(default.get("maxDanger") or "R3")


def escalate_danger(
    spec: ToolSpec,
    arguments: dict[str, Any] | None,
    *,
    foreground_process: str | None = None,
) -> DangerLevel:
    """上下文升级：键入到终端 = 执行命令；点击到高危文案 = 不可逆动作。"""
    arguments = arguments or {}
    danger: DangerLevel = spec.danger
    if spec.name in ("desk_type", "desk_key") and is_terminal_process(foreground_process):
        return "R3"
    if spec.name == "desk_click" and label_hits_high_risk(str(arguments.get("label") or "")):
        return "R3"
    return danger


def check_evidence(spec: ToolSpec, danger: DangerLevel, arguments: dict[str, Any] | None) -> Decision | None:
    """来源护栏：屏幕内容触发的高危动作直接拒绝（返回 Decision），否则返回 None。"""
    arguments = arguments or {}
    evidence = str(arguments.get("evidence") or EVIDENCE_USER)
    if evidence == EVIDENCE_SCREEN and danger == "R3":
        return Decision(
            allowed=False,
            needs_confirm=False,
            danger=danger,
            reason="screen_content_high_risk",
        )
    return None


def decide(
    spec: ToolSpec,
    arguments: dict[str, Any] | None,
    *,
    policy: dict[str, Any] | None = None,
    role: str | None = None,
    foreground_process: str | None = None,
    auto_confirm_low_risk: bool = False,
) -> Decision:
    """Python 侧预过滤。返回 allowed/needs_confirm/danger/reason。"""
    active = policy or load_policy()
    arguments = arguments or {}

    if spec.kind != "desktop":
        return Decision(True, False, spec.danger)

    # 1) 先拒：敏感前台进程
    if is_sensitive_process(foreground_process, active):
        return Decision(False, False, spec.danger, "sensitive_window")

    danger = escalate_danger(spec, arguments, foreground_process=foreground_process)

    # 2) 来源护栏（R3 + 屏幕内容）
    guard = check_evidence(spec, danger, arguments)
    if guard is not None:
        return guard

    # 3) 角色上限
    max_danger = _role_max_danger(active, role)
    if _RISK_ORDER.get(danger, 3) > _RISK_ORDER.get(max_danger, 2):
        return Decision(False, False, danger, "policy_blocked")

    # 4) 确认要求：R3 永远要问；R2 看用户是否勾了「低危免确认」
    needs_confirm = spec.needs_confirm or danger == "R3"
    if danger == "R2" and auto_confirm_low_risk:
        needs_confirm = False
    return Decision(True, needs_confirm, danger)


__all__ = [
    "DEFAULT_POLICY",
    "Decision",
    "EVIDENCE_SCREEN",
    "EVIDENCE_USER",
    "POLICY_PATH",
    "check_evidence",
    "decide",
    "escalate_danger",
    "is_sensitive_process",
    "is_terminal_process",
    "label_hits_high_risk",
    "load_policy",
]
