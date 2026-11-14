# Lagrange.Milky 单号跑通手册（Signer Token）

> 状态：**代码侧已就绪，只差一个签名 token**（2026-11-14 实测）。
> 读者：要开通 QQ 号的那台机器上的人（目前是你自己）。跟着做，做完把 token 交给 Agent。

---

## 1. 现在卡在哪（先说清楚，别重复踩）

| 环节 | 状态 | 证据 |
|---|---|---|
| 编译 `Lagrange.Milky` | ✅ 通过 | .NET SDK 10.0.404，`dotnet build lagrange-core\Lagrange.Milky\Lagrange.Milky.csproj -c Release` → 0 警告 0 错误 |
| 连 QQ 服务器 | ✅ 通 | 日志 `Connected to the server 111.30.169.83` |
| 进二维码登录 | ✅ 到达 | 日志 `Password is empty or null, use QRCode Login` |
| 调签名服务 | ❌ **401** | `sign.lagrangecore.org` 返回 `{"code":401,"message":"Missing or invalid authorization header"}` |

两个已经踩过的坑，别再犯：

1. **不要用 `sign.lagrangecore.com`**。克隆下来 `Lagrange.Milky/Resources/appsettings.json` 里的默认值就是 `.com`，那个域名已经废了（TLS 直接 `unrecognized name` / SSL alert 112，80 端口只有裸 404）。正确域名是 **`.org`**：
   `https://sign.lagrangecore.org/api/`。
2. **不要开 `GetOptimumServer`**（默认可能是 true）。它会用 ICMP ping 排服务器顺序，权限不足时报 `Win32Exception (5): 拒绝访问` 直接把启动打挂。配置里写 `"Server": { "GetOptimumServer": false }`。

另外本仓库对上游打了一个本地补丁（`local/lagrange-core/Lagrange.Milky/Login/LoginService.cs`）：
上游 `OnQrCode` 用 `FileOptions.DeleteOnClose` 写 `qrcode.png`，写完立刻被删，外部控制器读不到；
补丁改成保留文件，并额外写一份 `qrcode-url.txt` 放二维码里编码的 URL。**这是为了让网关照到二维码，不是上游行为。**

---

## 2. 拿 token 的官方流程

来源：[LagrangeDev/SignApiGuide](https://github.com/LagrangeDev/SignApiGuide)。白名单制，一个 GitHub 账号最多绑 **3 个**实际使用的 QQ 号。

### 2.1 先有一把「能加解密」的 GPG Key

必须是支持**解密**的算法（RSA 可以；Ed25519 只签名，不行）。本机已装 Git for Windows，gpg 在：

```
C:\Program Files\Git\usr\bin\gpg.exe
```

生成一把 RSA4096 的钥匙（把邮箱换成你 GitHub 账号绑定的邮箱）：

```powershell
$gpg = "C:\Program Files\Git\usr\bin\gpg.exe"
& $gpg --full-generate-key
# 选 (1) RSA and RSA，长度 4096，有效期按需（建议 2y），填姓名/邮箱/口令
```

导出公钥、上传到 GitHub（Settings → SSH and GPG keys → New GPG key）：

```powershell
& $gpg --armor --export "你的邮箱"
```

查 **主 Key 的 16 位 ID**（注意：要主 ID，不是 subkey）：

```powershell
& $gpg --list-secret-keys --keyid-format LONG
# 输出里 sec   rsa4096/AAAAAAAAAAAAAAAA 2026-11-14 [SC]
#                    ^^^^^^^^^^^^^^^^ 这 16 位就是要填的 Key ID
```

### 2.2 在带 signer bot 的 QQ 群里发三条命令

用**主 QQ 号**（不是要托管的那个号）在群里依次发：

```
#signer register <你的 GitHub 用户名> <16位 Key ID>
#signer verify <解密出来的 6 位码>
#signer bind <要托管的 QQ 号>
```

细节：

- 发完 `register` 后 bot 会用你的 GPG 公钥发一段加密文本，**5 分钟内**解密：
  `& $gpg --decrypt`（把 bot 那段粘贴进文件再解），得到 6 位码，立刻发 `verify`。
- 表情反馈：`/续标识`=已接收、`/喝彩`=处理成功、`/敲`=格式错、`/睡`=触发 1 分钟限频。
- 成功后 bot 会发一段 GPG 加密的 token，解密后得到 **UUID 形状的字符串**，这就是 `Signer.Token`。
- `bind` 的号才是「有签名权限」的号；主号本身不自动具备权限。
- 查已绑：`#signer profile`；解绑：`#signer unbind <QQ号>`；注销：`#signer delete`（注销后 3 天内不能再注册）。

---

## 3. 拿到 token 之后（两条路，选一条）

### 3.1 只想先看二维码（最快验证）

编辑 `local/lagrange-run/bot1/appsettings.json`：把 `Lagrange.Protocol.Signer.Token` 填上、`Login.Uin` 改成绑定的 QQ 号，然后：

```powershell
$exe = "local\lagrange-core\Lagrange.Milky\bin\Release\net10.0\Lagrange.Milky.exe"
Start-Process -FilePath $exe -ArgumentList '--no-stdin','--no-stdout' `
  -WorkingDirectory "local\lagrange-run\bot1" `
  -RedirectStandardOutput "local\lagrange-run\bot1\out.log" `
  -RedirectStandardError  "local\lagrange-run\bot1\err.log" -WindowStyle Hidden
# 等 10~20 秒，看 out.log 里是否出现 QRCode 阶段，然后：
Get-ChildItem "local\lagrange-run\bot1"    # 应出现 qrcode.png / qrcode-url.txt
```

验收：`qrcode.png` 出现 = 签名通过、拿到二维码；`out.log` 里不再出现 `401`。

### 3.2 交给网关（当前没有这条路，先别试）

`channel-gateway` 现在只会说 OneBot 11。接 Milky 需要新写一个协议适配器（HTTP `POST /api/<handler>` + WS `ws://127.0.0.1:<port>/event`），见第 5 节。

---

## 4. 已知的现实障碍（决定这条路能不能规模化）

1. **入口没公开**：指南只说「在包含该 bot 的群聊中」，没写群号/链接。上游仓库的 issue [#1](https://github.com/LagrangeDev/SignApiGuide/issues/1) 问的正是同一件事（「当前可用的 signer Bot 所在群或其他官方申请入口是什么？」），**至今没有回复**。
2. **官方签名被传「拉闸」**：第三方论坛（如 OlivOS 论坛）已有「现官方签名已经拉闸」的说法。
3. **3 个号上限**：一个 GitHub 账号只能绑 3 个实际使用的 QQ 号。对「每个用户自带一个 QQ 号」的产品模型来说，这意味着**每个用户都得自己走一遍 GPG + 群 + bind**——比当初装 NapCat 更重。
4. 对比：官方 QQ 机器人（q.qq.com）不需要任何签名服务，但要过审核、且一个 bot 只有一个身份。

结论：**这条路的单号验证值得做（就 3 个号，够 PoC），但不要假设它能承载 BYO 的商业形态。**

---

## 5. 网关接 Milky 需要做的事（给下一个 Agent）

Milky 协议面（本地源码实测，`local/lagrange-core/Lagrange.Milky/`）：

- 事件：`ws://127.0.0.1:<HttpServer.Port>/event`，有 `Milky.AccessToken` 时校验 `Authorization: Bearer <token>`（`Events/WebSocketEventHandler.cs:49-53`）。
- 事件信封：`{ event_type, time, self_id, data }`（`Events/MilkyEvent.cs`）；消息事件 `event_type = "message_receive"`。
- 消息体：`{ peer_id, message_seq, sender_id, time, segments[] }`，用 `message_scene` 区分 `friend` / `group` / `temp`；群消息额外带 `group` 与 `group_member`（`Models/Messages/*`）。
- 段：`{ type: "text" | "mention" | "reply" | "image" | ..., data: {...} }`（`Models/Segments/SegmentBase.cs`）。
- API：`POST /api/<name>`，body 为 JSON，返回 `{ status, retcode, message, data }`（`Api/MilkyApiResponse.cs`）；名字来自 `[ApiHandler("...")]`，已实现 43 个，含 `send_private_message`（body `{ user_id, message: [段...] }`）、`send_group_message`、`get_login_info`、`get_friend_list`、`get_group_list` 等。
- 没有「取二维码」的 API：二维码只能靠第 1 节那个补丁落盘的 `qrcode.png`。

落点建议：新增 `channel-gateway/src/milkyClient.js`（HTTP + WS）+ `milkyExtract.js`（段解析），`src/pool.js` 按 `cfg.protocol` 选 OneBot / Milky 适配器，`src/launch.js` 换成拉起 `Lagrange.Milky.exe` 并给每个号独立 `appsettings.json` 与工作目录。
