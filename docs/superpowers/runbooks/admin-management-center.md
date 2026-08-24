# LianYu 管理中枢运行手册

## 构建与启动

Admin 是独立 Windows Electron 应用，服务器只运行 Spring Boot 管理 API。

```powershell
cd admin
npm install
npm run electron:build
```

产物为 `admin/release/LianYu-Admin-Setup-<version>.exe`。Admin EXE 不包含数据库、MinIO、GitHub 或基础设施密钥。

## 首个超级管理员

后端迁移完成前设置：

```text
LIANYU_ADMIN_BOOTSTRAP_USERNAME=<账号>
LIANYU_ADMIN_BOOTSTRAP_PASSWORD=<一次性初始密码>
```

首次启动只在不存在活跃 `super_admin` 时创建账号；已有超管不会被覆盖。登录后立即修改初始密码，并在安全设置中启用可选 2FA。

## 权限与会话

- `super_admin` 是受保护角色，最后一个活跃超管不能被停用。
- 运营角色默认只能访问用户读取、版本、公告和健康信息。
- 登录失败 5 次会锁定 15 分钟；管理员会话同时受 Sa-Token 和 `admin_session` 持久化记录约束。
- 退出登录或撤销会话后，数据库记录立即失效。

## 版本流程

版本号使用 SemVer，渠道只允许 `stable` / `beta`。状态按 `draft → uploading → validating → ready → published → rollout` 流转；非法跳转会被拒绝。安装包文件名、大小和 SHA-512 在元数据校验接口验证，发布/回滚操作必须追加审计记录。

当前版本文件上传和 GitHub Release 导入接口仍在后续发布批次中接入；在此之前只允许创建草稿和执行服务端元数据校验，不应把草稿当作可下载版本发布。

## 审计

审计记录只追加，不提供删除接口。密码、令牌、TOTP 秘钥、API Key 等字段在写入详情前统一脱敏。

## 验收命令

```powershell
cd backend
$env:JAVA_HOME='D:\Scoop\apps\temurin17-jdk\current'
mvn -s settings-docker.xml -pl lianyu-admin -am test

cd ..\admin
npm test -- --run
npm run build
npm run electron:build
```
