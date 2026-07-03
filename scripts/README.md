# scripts — 仓库级辅助脚本

与 `backend/scripts/`（Vault / Flyway 维护）不同，本目录放**可选**运维与诊断脚本。日常 `docker compose up` **不需要**执行这里的内容。

## 目录结构

| 目录 | 用途 | 示例 |
|------|------|------|
| **`/`（根）** | 稳定、常引用的脚本 | `_cloud_deploy_pull.py`、`_audit_installer_unpack.py` |
| **`diag/`** | 本地/云端**排障**（只读诊断） | `_diag_*.py`、`_debug_*.py` |
| **`ops/`** | **运维**：重启、修复、上传、检查 | `_restart_*.py`、`_repair_*.py` |
| **`smoke/`** | **冒烟**与外部 API 快测 | `_smoke_*.py` |
| **`e2e/`** | 端到端测试 | `lianyu_e2e.py` |

## 命名约定

- `_` 前缀：内部/维护脚本，非产品运行时依赖。
- 新增一次性排障脚本请放入 `diag/` 或 `ops/`，避免堆在根目录。

## 常用入口

```bash
# 云端拉代码并重建 backend + api-gateway
python scripts/_cloud_deploy_pull.py

# Electron 安装包红队审计（解包 + grep）
python scripts/_audit_installer_unpack.py
```
