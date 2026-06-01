# secrets — 敏感文件目录（不入 Git）

| 文件 | 说明 |
|------|------|
| `platform-keys.txt.example` | 轮换团队 10-key 池时的格式示例 |
| `platform-keys.txt` | 明文 sk（**gitignore**，仅维护脚本使用） |

新人 clone：只需根目录 `.env` 里的 `LIANYU_MASTER_KEY`，**不必**创建 `platform-keys.txt`。
