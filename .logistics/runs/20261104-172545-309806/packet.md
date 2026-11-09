# 后勤主编材料 · 20261104-172545-309806

> 这是给主会话模型（主编）的候选材料，不是最终报告。
> 去重、定级、取舍、模块组织由主编决定；机器只负责收集与校验。

## 运行
- 根目录：`C:/Users/hp/Desktop/LianYu-PC`
- 范围：1100 个文件 / 12 个审查单元 / 135978 行
- 测试命令：（未检测到）

## 覆盖
- 预期单元 / 专家：security
- 已收到：security
- 缺失：（无）
- 无效：（无）
- 失败：（无）
- partial：（无）
- 可选未选：perf

## 单元覆盖矩阵

| 单元 | 标签 | security | perf |
| --- | --- | --- | --- |
| u01 | backend/lianyu-service (1/3) | reviewed | missing |
| u02 | backend/lianyu-service (2/3) | reviewed | missing |
| u03 | frontend/src (1/2) | sampled | missing |
| u04 | scripts (1/2) | sampled | missing |
| u05 | backend/lianyu-dao | reviewed | missing |
| u06 | backend-agent | reviewed | missing |
| u07 | frontend/src (2/2) | sampled | missing |
| u08 | backend/lianyu-web | reviewed | missing |
| u09 | frontend/electron | reviewed | missing |
| u10 | 其他模块（(root)、.github、admin 等） | reviewed | missing |
| u11 | backend（小模块合集 59 文件） | sampled | missing |
| u12 | 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等） | sampled | missing |

## 专家摘要
### security · ok
- 摘要：全项目 12 个审查单元均已覆盖（9 个 reviewed、3 个 sampled，无 skipped）。最严重的问题是 api-gateway 把 /api/agent/** 原样反代到 Python agent，而 agent 侧只有「可选 X-User-Id 相等」这一层形同虚设的校验，构成匿名跨租户读写记忆/会话的可复现路径；同一入口还能通过 LLM 网关的任意 base_url 触发内网 SSRF。其余为：Jasypt 缺主密钥时静默降级为明文落库、改密不失效旧会话、公开文件端点可匿名读取非公开对象、部署脚本 root 口令与 redis-cli -a、CI action 未按 SHA 固定。Java 侧鉴权/租户过滤、WebSocket 订阅校验、MinIO 上传白名单、前端 XSS/Token 存储、Electron 加固整体良好。
- sections：auth=1 · authz=1 · injection=0 · browser=0 · uploads=1 · secrets=1 · crypto=1 · exposure=1 · supply-chain=1
- notes：覆盖方式：先读 scope.json 拿 12 个单元，再对每个单元做 rg/ops_grep 模式扫描（鉴权注解、getLoginIdAsLong、${}、v-html、innerHTML、webPreferences、localStorage、actions@、ssh/redis 口令、SecretKey 等），然后读关键文件确认到行。u01/u02/u05/u06/u08/u09/u10 为 reviewed；u03/u04/u07/u11/u12 因体量按模式扫描+抽样，标 sampled。injection 与 browser 两个 section 检查后无 finding（Java Mapper 全为 #{} 占位、Python SQL 有只读/表名白名单；全仓 v-html 4 处均经 DOMPurify 或静态，CSP 与 Electron 隔离配置齐全），故计 0 而非跳过。handoff 仅写入运行目录，未改动任何源码；未提交、未删除文件。
- 发现数：7
- 转交其他专家：5 条
  - → 未指定 · admin 桌面端（admin/electron/main.js:7）未开启 sandbox：preload 仅暴露 3 个窗口 IPC、无远程内容，风险很低，未单列 finding
  - → 未指定 · RabbitMQ 默认口令 guest/guest（docker-compose.yml:294、application.yml:70）仍为弱默认值，但容器只绑 127.0.0.1，未列为独立 finding，建议随部署加固一起替换
  - → 未指定 · backend-agent 的 /agent/capabilities、/agent/health 披露内部能力清单，仅在网关加固后仍有信息价值，随 authz 条一并修复
  - → 未指定 · SQL 注入面已全量扫描（Java 599 文件 0 命中 ${}、Python 只读/插入白名单），无 finding；后续新增 Mapper 时建议加静态检查
  - → 未指定 · 未审查 .env.example（读取被凭据策略拒绝），环境变量清单以 docker-compose.yml 与 application.yml 的占位为据

### perf · not-selected
- 可选专家，本次未选择（不是缺失）。

## 候选 findings
### SEC-B2BD5 · [严重] api-gateway 无鉴权反代 /api/agent/**，Python 侧「可选 X-User-Id」可被绕过，导致匿名跨用户读写记忆与会话
- 专家 / section：security / authz
- 位置：`deploy/api-gateway/nginx.conf:252`
- 置信度：high · kind：plan
- 证据：nginx.conf `location /api/agent/` 只做限流与 `rewrite ^/api(/agent/.*)$ $1 break; proxy_pass $agent_upstream;`，未校验 Sa-Token、未剥离客户端自带的 X-User-Id；注释自认「外部请求必须带 X-User-Id 且与 body.userId 一致（app 侧再做二次校验）」。而 backend-agent/app/main.py 全部 25 个路由的校验都是 `user_id_header: str | None = Header(default=None, alias="X-User-Id")` 加 `if user_id_header and str(body.user_id) != user_id_header: raise HTTPException(403, "user mismatch")`（如 main.py:918-919、988-989）；不带该头时整段校验被跳过。更有 /agent/memory/relay/drain（959）、/agent/memory/relay/requeue（967）、/agent/memory/reconcile（974）、/agent/prompt/render（614）、/agent/prompt/scene-instruction（639）完全没有该校验。写接口 /agent/memory/write（982-1003）直接 `write_memories(body.user_id, body.character_id, ...)`。agent 容器持有生产库只读凭据（docker-compose.yml:112-117）且 agent 自身无对外端口，唯一外网入口就是该 nginx location。
- 影响：未登录的攻击者直接 POST https://<域名>/api/agent/memory/write 并自填 userId，即可为任意用户写入/覆盖记忆；/agent/chat-turn/stream、/agent/text/chat 可冒用他人身份对话；/agent/voice/delete 可删除他人音色；/agent/memory/relay/drain 可驱动 outbox 投影。属于可复现的跨租户读写与身份冒用，且无需任何凭据。
- 修复摘要：把 /api/agent/** 恢复为纯内网通道：网关层拒绝外部来源，Python 侧强制服务间共享密钥并把 X-User-Id 变成必填项，不再存在「带不带由客户端决定」的校验分支。
- 修复文件：deploy/api-gateway/nginx.conf、backend-agent/app/main.py、backend-agent/app/security.py、backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java、docker-compose.yml、.env.example
- 建议测试：pytest：不带 X-Agent-Token 请求 /agent/memory/write 断言 401；带密钥但缺 X-User-Id 断言 401；X-User-Id 与 body.userId 不一致断言 403；curl 从公网 IP 访问 https://<域名>/api/agent/memory/write 断言 403（nginx 层）；现有 AgentRuntimeClient 集成测试保持通过（合法链路带密钥仍 200）

### SEC-4D2AD · [高] Python agent 的 LLM 网关接受任意 base_url 且无出站 URL 校验，可被用作内网 SSRF 跳板
- 专家 / section：security / uploads
- 位置：`backend-agent/app/gateway.py:413`
- 置信度：high · kind：plan
- 证据：gateway.py:92-111 `normalize_openai_base_url` 只做去尾斜杠、去结尾 /v1 再拼 `/v1/chat/completions`，没有任何 host 白名单；413-414 直接 `client.post(url, ...)`。http_client.py:25-40 `new_client()` 只设置 timeout/UA/local_address，无 URL 校验。调用链：/agent/desktop-observe 的 body.textBaseUrl → desktop_observe.py:86-101 `gateway.chat(base_url=request.text_base_url)`；/agent/proactive-turn 同理（graphs/proactive_turn.py:118,149）。Java 侧有 OutboundUrlValidator（拒绝 localhost/127.0.0.1/0.0.0.0/169.254.169.254/metadata.google.internal/.local/.internal/ftp）与 SsrfPinningClientFactory，但 Python 侧没有任何等价物。
- 影响：配合本报告 authz 条的匿名 /api/agent/ 入口，攻击者指定 base_url=http://mysql:3306、http://redis:6379 或 http://169.254.169.254/latest/meta-data/ 即可让 agent 容器向内网发起 POST 并回显错误内容，形成内网探测与云元数据访问跳板；即使网关加固，任何能调用 /agent/* 的内网组件同样可滥用。
- 修复摘要：在 Python 侧补一层与 Java OutboundUrlValidator 等价的出站校验（协议白名单 + 禁止私网/环回/链路本地/元数据地址 + 解析后二次校验），并让 base_url 只能来自服务端受信配置。
- 修复文件：backend-agent/app/gateway.py、backend-agent/app/url_guard.py、backend-agent/app/http_client.py、backend-agent/app/desktop_observe.py、backend-agent/app/graphs/proactive_turn.py
- 建议测试：pytest tests/test_url_guard.py：断言 http://127.0.0.1、http://mysql:3306、http://169.254.169.254/latest/meta-data/、ftp:// 均被拒绝；gateway.chat 单测：非法 base_url 返回 400 且不产生 socket 连接（mock client 断言未被调用）；公网域名（如 https://api.example.com）仍可通过

### SEC-79B9D · [中] 匿名 /api/public/files/** 不校验对象键白名单，用户私有语音/图片可被无鉴权读取
- 专家 / section：security / exposure
- 位置：`backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java:31`
- 置信度：high · kind：plan
- 证据：PublicFileController 注解 `@GetMapping("/api/public/files/**")` 匿名可访问（SaTokenConfig 放行 /api/public/**），extractObjectKey 只做 `requestUri.substring(idx + FileStorageService.PUBLIC_FILE_PREFIX.length())`（72-79），没有对 objectKey 套 FileStorageService.SAFE_OBJECT_KEY，也没有任何归属校验，随后直接 statObject/getObject 读同一个 bucket。而 SAFE_OBJECT_KEY 明确包含 `chat-voice/<petId>/<uuid>.wav`、`custom-voices/<userId>/<uuid>` 等私有前缀（FileStorageService.java:50-52），toPublicUrl 还会把这类 key 拼成 /api/public/files/... 返回给前端（:468-470）。响应固定带 `Access-Control-Allow-Origin: *`（62）与 `Cache-Control: public, max-age=86400`（88）。
- 影响：私有语音（用户自录音色）与聊天图片落在同一 bucket 且可被匿名端点按 key 读取：一旦 URL 从日志、截图、分享链接、客服工单泄露，任何人可在浏览器/Electron 中直接取到音频并长期缓存（24h public cache），且没有吊销手段；「public」前缀实际上不构成访问边界。
- 修复摘要：把公开端点收敛为显式公开前缀白名单，私有前缀改走需要登录+归属校验的读取端点（或 MinIO 短时预签名 URL）。
- 修复文件：backend/lianyu-web/src/main/java/com/lianyu/web/controller/PublicFileController.java、backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java、deploy/api-gateway/nginx.conf
- 建议测试：MockMvc：GET /api/public/files/chat-voice/pet/x.wav 未登录断言 404/401；MockMvc：GET /api/public/files/avatars/a.png 仍 200 且保留 nosniff；新增归属测试：他人登录后访问不属于自己的 custom-voices 对象断言 403

### SEC-C62DD · [中] Jasypt 缺 LIANYU_MASTER_KEY 时静默降级为明文，敏感字段（模型 Key、TOTP 密钥）以明文落库
- 专家 / section：security / crypto
- 位置：`backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java:61`
- 置信度：medium · kind：plan
- 证据：构造函数在 `LIANYU_MASTER_KEY` 缺失时只 `log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled. Set env var before production use.")` 然后 return（22-25），不阻断启动；encrypt() 在 `currentVersion == null || encryptors.isEmpty()` 时 `log.warn("No Jasypt encryptor available, returning plaintext"); return plaintext;`（61-71）；decrypt() 在 encryptors 为空时原样返回密文（75-80）。EncryptedStringTypeHandler.setNonNullParameter（16 行）调 encrypt()，所以缺密钥时写入 DB 的就是明文。
- 影响：部署漏配一个环境变量即静默退化为「无加密」：模型 API Key、管理员 totp_secret 等会以明文进入 MySQL，并随备份/只读副本/慢查询日志扩散；日志只有一条 warn，运维极易漏看，且事后无法从库中分辨哪些行是明文。
- 修复摘要：改为 fail-closed：生产环境缺主密钥直接启动失败，encrypt() 不再返回明文；并加入启动自检识别历史明文行。
- 修复文件：backend/lianyu-security/src/main/java/com/lianyu/security/util/JasyptUtil.java、backend/lianyu-security/src/main/java/com/lianyu/security/handler/EncryptedStringTypeHandler.java、docker-compose.yml、.env.example
- 建议测试：JUnit：无 LIANYU_MASTER_KEY 时 encrypt 抛异常（而非返回明文）；JUnit：prod profile 缺 key 时 ApplicationContext 启动失败；集成测试：配置 key 后写入再读取，DB 列值不等于明文

### SEC-B7C0A · [中] 修改密码不失效其它会话，且 refresh 每次续签 30 天无绝对上限
- 专家 / section：security / auth
- 位置：`backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java:154`
- 置信度：high · kind：plan
- 证据：changePassword（152-162）只 `user.setPasswordHash(passwordEncoder.encode(request.getNewPassword())); userMapper.updateById(user); log.info("User password changed: {}", ...)`，没有 StpUtil.kickout、没有 token 版本号、没有清理该用户其它 Sa-Token 会话；refresh（89-99）每次 `StpUtil.renewTimeout(SaManager.getConfig().getTimeout())`（默认 2592000 秒），session 无绝对有效期；is-concurrent:true && is-share:false（application.yml:133-146）允许同账号多设备各自长期有效。
- 影响：凭据泄露后受害者改密码无法止损：攻击者已持有的 token 仍可继续调用全部 /api/** 并每次 refresh 续签到 30 天，理论可无限期保活；同一泄露 token 在多设备场景下无法被单点吊销。
- 修复摘要：引入会话版本号（或改密即踢出全部会话），并给 refresh 增加绝对有效期，避免改密后旧 token 仍可用。
- 修复文件：backend/lianyu-service/src/main/java/com/lianyu/service/auth/impl/AuthServiceImpl.java、backend/lianyu-security/src/main/java/com/lianyu/security/config/SaTokenConfig.java、backend/lianyu-dao/src/main/resources/db/migration/
- 建议测试：AuthServiceImplTest：改密前拿到的 token 在改密后请求 /api/users/me 返回 401；refresh 超过绝对期限的 token 返回 401 且不续签；多设备登录后改密，两个旧 token 均失效

### SEC-44999 · [低] 部署脚本以 root 口令 SSH 并硬编码生产主机，且 redis 口令以 -a 出现在命令行
- 专家 / section：security / secrets
- 位置：`scripts/_cloud_deploy_pull.py:125`
- 置信度：medium · kind：plan
- 证据：scripts/_cloud_deploy_pull.py:49-56 硬编码 `host = "156.233.228.18"`、`username = "root"`，口令来自 os.environ["DEPLOY_SSH_PASSWORD"]（未硬编码，但以 root 身份登录）；125-126 与 _cloud_deploy_layered.py:97-98 用 `redis-cli -a "${REDIS_PASSWORD}"` 形式把口令放进命令行。同类硬编码主机/账号另见 _check_yml.py:4、_cloud_500_check.py:27、_cloud_env_check.py:40 等。全仓 sk-/AIza/ghp_/PRIVATE KEY 实值扫描为 0 命中。
- 影响：redis 口令会出现在服务器进程列表（ps）、shell history 与容器 exec 日志中，同机低权用户可读取；以 root 口令做部署意味着任何读过脚本历史的人都知道该打哪台机器、用哪个账号，泄露即等于拿到生产 root 的完整攻击面。
- 修复摘要：改用 SSH key + 非 root 部署账号，redis 口令改走环境变量而不是命令行参数，主机/账号外移到环境配置。
- 修复文件：scripts/_cloud_deploy_pull.py、scripts/_cloud_deploy_layered.py、scripts/_cloud_env_check.py、.env.example
- 建议测试：本地 dry-run 部署脚本，确认命令行里不再出现口令（ps 快照断言）；以 deploy 账号跑一次只读健康检查脚本成功；gitleaks 扫描 scripts/ 0 命中

### SEC-33910 · [低] CI 第三方 action 仅按 tag 固定，且依赖漏洞扫描 continue-on-error 不阻断合并
- 专家 / section：security / supply-chain
- 位置：`.github/workflows/ci.yml:14`
- 置信度：high · kind：direct
- 证据：ci.yml 中 `uses: actions/checkout@v4`（14）、`actions/setup-java@v4`（15）、`actions/setup-node@v4`（28/29）、`google/osv-scanner-action@v2.0.3`（45）全部是可变 tag，未按 commit SHA 固定；deps-audit job 带 `continue-on-error: true`，osv-scanner 发现漏洞也不会让流水线红。
- 影响：上游 action 仓库被劫持或 tag 被重写时，构建机可直接执行任意代码并读取仓库 secrets（发布 token 等）；同时已知依赖漏洞不会阻断合并，供应链风险只能靠人工发现。
- 修复摘要：把四个外部 action 固定到 40 位 commit SHA，并让 osv-scanner 的发现阻断合并（或只对显式豁免放行）。
- 修复文件：.github/workflows/ci.yml、.github/dependabot.yml
- 建议测试：actionlint 校验 workflow 语法通过；推送测试分支，确认 CI 四个 job 全部成功且 osv 步骤为阻断状态

## 转交 deferred
- security → 未指定 · admin 桌面端（admin/electron/main.js:7）未开启 sandbox：preload 仅暴露 3 个窗口 IPC、无远程内容，风险很低，未单列 finding
- security → 未指定 · RabbitMQ 默认口令 guest/guest（docker-compose.yml:294、application.yml:70）仍为弱默认值，但容器只绑 127.0.0.1，未列为独立 finding，建议随部署加固一起替换
- security → 未指定 · backend-agent 的 /agent/capabilities、/agent/health 披露内部能力清单，仅在网关加固后仍有信息价值，随 authz 条一并修复
- security → 未指定 · SQL 注入面已全量扫描（Java 599 文件 0 命中 ${}、Python 只读/插入白名单），无 finding；后续新增 Mapper 时建议加静态检查
- security → 未指定 · 未审查 .env.example（读取被凭据策略拒绝），环境变量清单以 docker-compose.yml 与 application.yml 的占位为据

> 这些不是当前专家的 finding，但必须写入 verdict.deferred，不能静默丢弃。

## 机器提示（仅供参考）
- 没有检测到明显的跨专家重复候选。

## 交给主编
1. 读本文件与原始 handoff；不要只依赖机器提示。
2. 合并重复项，保留最可信的证据；被合并的 ID 记入 verdict.dropped。
3. 写 `verdict.json`（schemaVersion 2、findings、coverage、dropped），再写 `report.md`。
4. 只有完整覆盖默认四模块时，才更新 `docs/logistics/latest.md` 与 `.logistics/latest.json`。
5. 工作区根目录：`C:\Users\hp\Desktop\LianYu-PC`；运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-172545-309806`。
