# LianYu Admin

独立 Windows 管理中枢。服务器只提供 `/api/admin/v1/**` 管理 API，Admin EXE 不携带数据库、MinIO、GitHub 或基础设施密钥。

## 本地运行

```powershell
npm install
npm run build
npm run electron:dev
```

## 首个超管

后端启动前设置 `LIANYU_ADMIN_BOOTSTRAP_USERNAME` 与 `LIANYU_ADMIN_BOOTSTRAP_PASSWORD`。首次迁移完成后自动创建受保护的 `super_admin` 角色绑定账号；已有活跃超管时不会覆盖。
