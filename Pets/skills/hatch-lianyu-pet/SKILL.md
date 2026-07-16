# Hatch LianYu Pet — Q版桌宠制作流程

## 用途

从一张角色 Q 版立绘，制作一个可用的 LianYu 桌宠角色。

图像生成和 atlas 拼合的核心流程**不在本 skill 重复**，而是直接引用 `awesome-codex-pet` 仓库的 `hatch-pet-v1` 技能。本 skill 只负责：项目特有的适配、接入、部署、踩坑记录。

---

## 依赖的外部流程

**图像生成 + 逐帧提取 + 拼合 atlas 的完整流程由以下仓库定义，Agent 必须先阅读：**

- **仓库**：https://github.com/legeling/awesome-codex-pet
- **技能路径**：`.agents/skills/hatch-pet-v1/SKILL.md`
- **脚本目录**：`.agents/skills/hatch-pet-v1/scripts/`

该技能包含：`prepare_pet_run.py`（准备 prompt 和 job 清单）、`finalize_pet_run.py`（提帧 + 拼 atlas + QA）、`compose_atlas.py`、`extract_strip_frames.py` 等确定性脚本。

**Agent 在开始制作桌宠前，必须先读上面那个 `SKILL.md`，理解它的完整流程后再继续。**

本 skill 不重复这些流程，只补充本项目的适配差异。

---

## 制作前必问用户的问题

**在开始任何图像生成之前，必须先逐个问用户以下问题，确认后才能继续。** 不要跳过、不要自己替用户选默认值。

### 问题 1：头身比

```
你希望角色 Q 版桌宠的整体比例是哪种？

A. 标准 Codex 宠物比例（头身约 1:1，身体完整，适合跑动和拖拽）
B. 大头萌系比例（头身约 1.2:1，脸和表情更突出，动作幅度较小）
C. 小型礼服比例（头身约 0.8:1，更突出长发、裙摆等装饰，但桌面缩小时识别度略低）
```

用户回答后，将比例写进 `prepare_pet_run.py` 的 `--pet-notes` 和 `--style-notes`。

### 问题 2：像素风格

```
你希望像素表现偏哪种风格？

A. 精致像素风（轮廓清楚、颜色层次较多、表情细腻，保留发丝高光和装饰细节）
B. 复古低分辨率像素风（色块更少、边缘更硬、更像传统游戏精灵）
C. 软萌高色彩像素风（高光和渐变更多，视觉更华丽，但缩小后细节可能变糊）
```

### 问题 3：动作气质

```
根据这个角色的性格，你希望桌宠动作整体偏哪种气质？
（参考选项，也可以自己描述，最终以角色本身的性格为准）

A. 甜美优雅（待机轻摆，招手带微笑，跳跃轻盈）
B. 活泼调皮（动作幅度大，跑动轻快，失败动作带夸张反应）
C. 温柔安静（动作舒缓，少装饰效果）
D. 威严冷酷（动作克制有力，失败反应冷淡）
E. 其他（请描述）
```

气质选项不是固定的——不同角色适合不同的气质。Agent 应该先了解角色性格（查角色资料或问用户），再给出贴合该角色的候选选项，而不是机械套用上面 4 个。用户回答后，将气质关键词写进 `--pet-notes`，它会出现在每一行 row prompt 的身份锁定段。

### 问题 4：是否需要语音

```
这个角色需要接入语音吗？

A. 需要，我有录音可以用来做声音复刻
B. 暂不需要，先完成图集和目录接入
C. 只需要台词风格，不绑定语音
```

如果选 A，继续问录音文件路径，并提前处理成单声道 WAV。如果选 B 或 C，跳过第八步的语音接入。

### 问题 5：参考图

```
请提供角色 Q 版立绘的路径。参考图放到 Pets/images/ 下了吗？
```

确认图片存在且可读后再继续。

---

## 前置条件

| 项 | 要求 |
|---|---|
| 参考图 | 一张角色 Q 版立绘（jpg/png），放到 `Pets/images/` 下 |
| gpt-image-2 API | url+key 存到 `C:\Users\hp\Desktop\APIKEY.txt`，格式为 `Pets-url：...` 和 `Pets-key：...` |
| 语音 API Key | 在 `.env` 中配置（用于声音复刻） |
| 录音（可选） | 如需定制语音，提供 10~60 秒单人清晰录音 |
| awesome-codex-pet | 需提前 sparse-clone 到本地，获取 hatch-pet-v1 脚本 |

---

## 本项目适配差异

awesome-codex-pet 的 hatch-pet 原始流程假设你可以直接调用 `$imagegen` 或 OpenAI 官方 API。本项目环境有以下差异，需要用本 skill 自带的适配脚本：

### 差异 1：图像生成用 gpt-image-2 兼容服务

hatch-pet 的 `$imagegen` 主链路在当前环境不可用（缺少内置 `image_gen` 工具）。官方后备脚本 `generate_pet_images.py` 调 OpenAI 官方域名，也不适用。

**用本 skill 的 `scripts/gen_pet_images.py` 替代**，它从 `APIKEY.txt` 读取 `Pets-url` 和 `Pets-key`，用 `curl` 提交，并自动重试异步 task。

### 差异 2：参考图目录

hatch-pet 不规定参考图位置。本项目统一放到 `Pets/images/`。

---

## 完整步骤

### 第一步：准备参考图

将角色 Q 版立绘放到 `Pets/images/` 目录。

### 第二步：拉取 awesome-codex-pet 的 hatch-pet-v1 脚本

```bash
# sparse-clone 到临时目录
git clone --depth 1 --filter=blob:none --sparse \
  https://github.com/legeling/awesome-codex-pet.git \
  C:\Users\hp\AppData\Local\Temp\opencode\awesome-codex-pet
cd C:\Users\hp\AppData\Local\Temp\opencode\awesome-codex-pet
git sparse-checkout set '.agents/skills/hatch-pet-v1'
```

设环境变量供后续步骤引用：

```bash
CODEX_SKILL="C:\Users\hp\AppData\Local\Temp\opencode\awesome-codex-pet\.agents\skills\hatch-pet-v1"
RUN_DIR="C:\Users\hp\Desktop\LianYu-PC\artifacts\<角色名>-pet-run-openai"
```

### 第三步：用 hatch-pet 准备运行目录

按 hatch-pet-v1 的 `SKILL.md` 执行 `prepare_pet_run.py`：

```bash
python "$CODEX_SKILL/scripts/prepare_pet_run.py" \
  --pet-name "<角色英文名>" \
  --description "A chibi <角色名> pet from <作品名>" \
  --reference "C:\Users\hp\Desktop\LianYu-PC\Pets\images\<参考图>" \
  --output-dir "$RUN_DIR" \
  --pet-notes "<角色名> from <作品名>: <外貌描述>" \
  --style-notes "Codex digital pet pixel-art style, chibi proportions, thick dark outline, flat cel shading, limited palette, 192x208 sprite" \
  --force
```

### 第四步：用 gpt-image-2 生成 base（先出一版给用户确认）

```bash
python Pets/skills/hatch-lianyu-pet/scripts/gen_pet_images.py \
  --run-dir "$RUN_DIR" \
  --job-id base
```

**⚠️ 关键确认点：生成 base 后必须暂停，把 `decoded/base.png` 给用户看，确认角色风格对了再继续。**

不要一口气跑完 9 行再给用户看——如果 base 风格就不对，后面 9 行全白跑。

用户确认后再继续：

```bash
python Pets/skills/hatch-lianyu-pet/scripts/gen_pet_images.py \
  --run-dir "$RUN_DIR" \
  --states all --skip-base
```

### 第五步：修补不满意的动作行

如果某行动作崩坏或角色不像：

1. 修改 `$RUN_DIR/prompts/rows/<动作>.md`，收紧身份锁定
2. 只重跑该行：
   ```bash
   python Pets/skills/hatch-lianyu-pet/scripts/gen_pet_images.py \
     --run-dir "$RUN_DIR" \
     --job-id <动作>
   ```
3. 重新拼 atlas（见第六步）

### 第六步：拼合 atlas

按 hatch-pet-v1 的 `SKILL.md` 执行 `finalize_pet_run.py`：

```bash
python "$CODEX_SKILL/scripts/finalize_pet_run.py" \
  --run-dir "$RUN_DIR" \
  --allow-slot-extraction \
  --skip-videos \
  --skip-package
```

这会产出 `$RUN_DIR/final/spritesheet.webp`（1536×1872）和 QA 文件。

### 第七步：复制正式资源到项目

```bash
cp "$RUN_DIR/final/spritesheet.webp" \
   "frontend/public/pet/<角色id>_spritesheet.webp"

ffmpeg -y -i "frontend/public/pet/<角色id>_spritesheet.webp" \
  -vf "crop=192:208:0:0" -frames:v 1 \
  "frontend/public/pet/<角色id>_idle0.png"
```

验证：spritesheet 必须是 1536×1872，idle0 必须是 192×208。

### 第八步：接入前端 petCatalog

在 `frontend/src/constants/petCatalog.js` 的 `PET_CATALOG` 末尾新增：

```js
{
  id: '<角色id>',
  nameZh: '<中文名>',
  nameEn: '<英文名>',
  nameJa: '<日文名>',
  series: '<作品名>',
  sprite: 'pet/<角色id>_spritesheet.webp',
  preview: 'pet/<角色id>_idle0.png',
  persona: '你是<角色名>，<角色性格描述，语气风格，交互方式>',
  voiceSource: 'vc',
},
```

### 第九步：接入后端语音（可选）

**方案 A：声音复刻（VC）— 需要录音**

1. 用 ffmpeg 将录音转为单声道 WAV：
   ```bash
   ffmpeg -i <录音> -vn -ac 1 -ar 48000 -c:a pcm_s16le artifacts/<角色id>-vc-source-mono.wav
   ```
2. 调用 VC enrollment 创建音色（参考 `scripts/enroll_pet_voices.py`）
3. 获取返回的 voice ID
4. 写入 `backend/lianyu-service/src/main/resources/pet-voices.json`
5. 补测试断言到 `PetVoiceRegistryTest.java`

**方案 B：暂不接入语音**

不写入 `pet-voices.json`，桌宠截图时只有文字问候，无音频播放。

### 第十步：更新前端 allowlist

在 `frontend/electron/desktopSettings.js` 的 `ALLOWED_PET_IDS` 补上新角色 id：

```js
const ALLOWED_PET_IDS = [
  'raiden', 'ayaka', ..., 'elysia', '<新角色id>',
]
```

**如果不做这步，前端切换到新角色后主进程会拒绝写入，桌宠不会切换。**

### 第十一步：部署

**前端改动**（petCatalog、desktopSettings、public 资源）：
- `cd frontend && npm run electron:release`

**后端改动**（pet-voices.json）：
- `python scripts/_cloud_deploy_pull.py`

**两类改动都要做时**：
- 先 `git commit && git push origin main`
- 再打 Electron 包
- 再部署后端
- 最后提交版本号变更并推送

---

## 本 skill 自带脚本

| 脚本 | 作用 |
|---|---|
| `scripts/gen_pet_images.py` | 用 gpt-image-2 兼容服务生成 base 和 9 行动作条，自动重试异步 task |
| `scripts/enroll_pet_voices.py` | 用 VC enrollment 创建自定义音色 |

---

## 文件清单

| 文件 | 改动 |
|---|---|
| `frontend/public/pet/<角色id>_spritesheet.webp` | 新增 |
| `frontend/public/pet/<角色id>_idle0.png` | 新增 |
| `frontend/src/constants/petCatalog.js` | 新增条目 |
| `frontend/electron/desktopSettings.js` | ALLOWED_PET_IDS 补 id |
| `backend/lianyu-service/src/main/resources/pet-voices.json` | 新增 voice 映射（可选） |
| `backend/.../PetVoiceRegistryTest.java` | 新增测试断言（可选） |

## atlas 规格

- 尺寸：1536×1872
- 网格：8 列 × 9 行
- 单格：192×208
- 动作行：idle(6) / running-right(8) / running-left(8) / waving(4) / jumping(5) / failed(8) / waiting(6) / running(6) / review(6)

---

## 踩坑记录与避坑指南

以下是实际开发爱莉希雅桌宠时遇到的问题和浪费时间的弯路，后续角色务必避免重复。每条都包含：现象、原因/后果、解决方式、教训。

### 坑 1：用程序化像素画硬画 atlas — 废品

**现象**：直接用 Node.js + Canvas 手绘 chibi 像素角色，拼成 atlas。尝试了两轮，第二轮还从参考图提取了真实配色（樱花粉 `rgb(240,192,224)`、暖白 `rgb(245,235,237)`）。
**结果**：角色完全不像参考图里的爱莉希雅，用户直接否定，说"很丑"并删掉了生成的文件。
**原因**：程序化硬画的上限太低——椭圆+矩形拼出来的 chibi 不可能有参考图的辨识度，即使颜色对了，轮廓和细节也完全不像。
**正确做法**：必须走 AI 图像生成（gpt-image-2），不要用代码画像素画。不管颜色提取多精确、轮廓画多仔细，程序化硬画都达不到"像参考图角色"的效果。
**浪费时长**：2 轮完整生成 + 用户否定 + 删除重来。

### 坑 2：用通义万相替代 gpt-image-2 — 效果不稳定

**现象**：因为环境没有 gpt-image-2 的 key，临时把 hatch-pet 的图像生成换成通义万相（DashScope text2image API）。图能成功生成，也跑通了整套 hatch-pet 提帧和拼合流程。
**结果**：生成的图不遵守"多格 sprite strip 严格布局"——AI 没有按 layout guide 把角色排成一整行，导致 hatch-pet 的 `extract_strip_frames.py` 只能走 `slots` 模式硬切，角色被压缩、残缺、比例失真。用户看到后说"完全不行，连完整的角色都没有"。
**原因**：通义万相不是为"严格多格动作条"设计的，在这种"多帧连续动作 + 固定格数 + 透明背景"任务上，布局控制能力远不如 gpt-image-2。
**正确做法**：图像生成必须用 gpt-image-2，不要用通义万相或其他模型替代。如果当前环境没有 gpt-image-2 key，先搞到 key 再开始，不要用替代品将就。
**浪费时长**：1 轮完整生成（base + 9 行）+ 用户否定 + 重新换方案 + 适配新 API。

### 坑 3：hatch-pet 的 $imagegen 主链路在当前环境不可用

**现象**：hatch-pet 的 `SKILL.md` 要求调用 `$imagegen` 系统 skill 做图像生成。本机确实有 `$imagegen` 的 skill 文件（`C:\Users\hp\.codex\skills\.system\imagegen\SKILL.md`），但当前 AI 会话的工具列表里没有它依赖的内置 `image_gen` 工具。
**结果**：skill 文件在磁盘上存在，但没法在会话里真正执行它的 built-in 路径。被迫走 hatch-pet 的官方后备脚本 `generate_pet_images.py`，但该脚本默认调 OpenAI 官方域名 `api.openai.com`，也不适用自定义兼容服务。
**解决**：写了一个适配脚本 `gen_pet_images.py`，从 `APIKEY.txt` 读取自定义兼容服务的 url+key，用 `curl` 提交，绕开了 requests 的 SSL 问题。
**教训**：`$imagegen` skill 文件存在 ≠ 当前会话能调用它的内置工具。开工前先测一次最小调用，确认环境真的能用，不要看文件在就假设能用。

### 坑 4：gpt-image-2 兼容服务返回异步 task — 请求卡死

**现象**：向兼容服务发 `images/edits` 请求，有时返回 `{"task_id":"task_xxx","status":"processing"}` 而不是直接返回图片 URL。响应消息提示"请稍后到异步任务中查看结果"。
**排查过程**：花了大量时间探测 task 查询接口——试了 `/v1/tasks/{id}`、`/v1/tasks/{id}/fetch`、`/v1/tasks/{id}/status`、`/v1/images/tasks/{id}`、`/v1/async/tasks/{id}`、`/v1/async-result/{id}` 等 20+ 条路径，全部 404。响应头暴露它是 OneAPI/NewAPI 风格的兼容层，但异步查询接口没有公开暴露。
**解决**：在请求中加 `async=false`、`stream=false`、`response_format=b64_json` 三个 form 参数，可以把同步返回概率提高很多；再配合脚本内的重试循环（最多 12 次，间隔 2 秒），基本能稳定拿到结果。不是每次都一次成功，但重试 2-3 次通常能拿到同步响应。
**教训**：兼容服务不一定暴露 task 查询接口，与其花时间探测，不如加参数强制同步 + 重试。单个 edit 请求偶尔走异步是正常的，重试就行，不要去探查询接口。
**浪费时长**：探测 20+ 条 404 路径，是最浪费时间的一步。

### 坑 5：多图 edit 比单图 edit 不稳定得多

**现象**：hatch-pet 的 row job 默认带 4 张输入图（参考图 + layout guide + canonical base + decoded base）。兼容服务对多图 edit 更容易走异步 task，同步返回率明显低于单图 edit。
**排查**：做了对照测试——单图 edit（只用 canonical base）在同样参数下同步返回率高；多图 edit（4 张）几乎每次都走异步。
**解决**：把脚本收敛为只用 1 张 canonical base 做 edit，其余布局和身份约束都交给 prompt 文本。单图 edit 的同步返回率远高于多图。
**教训**：不要贪多。1 张参考图 + 强 prompt 比多图 edit 更稳更快。即使 hatch-pet 默认带 4 张图，在本项目兼容服务上也应该收敛到 1 张。

### 坑 6：ffmpeg 编码 WebP 默认用了动画编码器 — 文件损坏

**现象**：用 ffmpeg 从 PNG atlas 转 WebP 时，`-c:v webp` 被 ffmpeg 自动解析成 `libwebp_anim`（动画 WebP 编码器），而不是 `libwebp`（静态编码器）。生成的 WebP 文件 ffprobe 报 `width=0 height=0` 和 `image data not found`。
**结果**：桌宠加载不出图，`usePetSpriteAnimator` 的 `Image.onerror` 触发，fallback 到纯 idle 动画。
**解决**：必须显式用 `-c:v libwebp`（静态编码器），不能用默认的 `-c:v webp`。另外还要加 `-pix_fmt bgra` 确保透明通道。
**教训**：ffmpeg 的 `-c:v webp` 不等于 `-c:v libwebp`。前者可能被路由到动画编码器，后者才是静态编码器。生成桌宠 atlas 这类静态 WebP 时，必须显式指定 `libwebp`。

### 坑 7：PowerShell here-string `python - << 'PY'` 不工作

**现象**：在 PowerShell 里用 `python - << 'PY' ... PY` 传多行 Python 代码，PowerShell 不支持 here-string 这种 Unix 语法，把 `<<` 当成重定向，多行内容被当成字符串拼接，报 `SyntaxError: unterminated string literal`。
**结果**：每次想跑一段 Python inline 代码都失败，浪费往返时间。
**解决**：把 Python 代码写到 `.py` 文件再用 `python 文件名` 执行。或者用 PowerShell 单行 + 分号写法（但复杂逻辑不推荐）。
**教训**：本项目 shell 是 PowerShell 5.1，不支持 bash here-string。需要跑多行 Python 时，永远先写文件再执行。

### 坑 8：APIKEY.txt 里多组凭据共存 — 要按前缀精确匹配

**现象**：`APIKEY.txt` 里同时存了 DeepSeek、gpt-image-2 等多组凭据。最初脚本按 `startswith("url")` 匹配 url 行，会误匹配到其他服务（比如 DeepSeek 的行也包含 url）。
**结果**：脚本取到了错误的 url 或 key，请求发到错误的服务。
**解决**：按 `Pets-url` 和 `Pets-key` 前缀精确匹配，不要用泛化的 `url`/`key` 前缀。脚本 `gen_pet_images.py` 已按此方式实现。
**教训**：多组凭据共存时，每行用服务名前缀区分（如 `Pets-url：`、`DeepSeek-Opencode：`），解析时按完整前缀匹配，不要偷懒用泛化前缀。

### 坑 9：Python requests 的 SSL 连接在兼容服务上不稳定

**现象**：用 Python `requests` 库提交到 gpt-image-2 兼容服务，有时报 `SSL: UNEXPECTED_EOF_WHILE_READING`（SSL 握手期间连接被对端关闭）。用 `Invoke-RestMethod` 也报同样的错。
**原因**：兼容服务在 Cloudflare 后面，requests 的 TLS 握手/连接复用和该服务的 CDN 不完全兼容。同样的请求用 `curl` 就能成功。
**排查**：先用单行 `python -c` + `requests` 测试连通性，确认 API 本身没问题（能返回 200 和图片 URL）。但脚本里用 `requests.post` 提交 multipart/form-data 时 SSL 不稳定。
**解决**：改用 `subprocess.run(["curl.exe", ...])` 提交 multipart/form-data，绕开 requests 的 TLS 路径。curl 对 Cloudflare 的 TLS 兼容性更好。
**教训**：如果 `requests` 在某个服务上 SSL 不稳定，先试 `curl`。不要花时间调试 requests 的 SSL 配置（`verify=False`、`cert` 等），直接换 curl 更快。

### 坑 10：桌宠切换不生效 — allowlist 漏了新角色 id

**现象**：前端设置页点了爱莉希雅，但桌宠显示的还是切换前的旧角色。其他角色之间切换正常（雷电将军切甘雨没问题），只有爱莉希雅这样。
**原因**：`frontend/electron/desktopSettings.js` 的 `ALLOWED_PET_IDS` 数组里没有 `elysia`。`writeDesktopSettings()` 在写入时检查 `launcherPetId` 是否在 allowlist 里，不在就 `delete sanitized.launcherPetId`——等于静默丢弃了用户的切换请求。主进程仍然保留旧桌宠。
**解决**：在 `ALLOWED_PET_IDS` 数组里补上新角色 id。
**教训**：每加一个新桌宠，必须同时更新三处：`petCatalog.js`（前端目录）、`desktopSettings.js`（主进程 allowlist）、`pet-voices.json`（后端语音映射）。缺任何一处都会出问题：缺 `petCatalog` 选不到，缺 `desktopSettings` 切不过去，缺 `pet-voices` 没声音。

### 坑 11：语音映射改了但后端没重新部署 — 有文字无声音

**现象**：爱莉希雅桌宠截图观察后，问候文字正常显示了，但没有语音播放。其他老角色（雷电将军、甘雨等）有声音。
**排查过程**：先怀疑是前端音频播放逻辑有问题，查了 `playGreetingAudio`、`audioBase64` 解析、autoplay 策略拦截等，都没问题。然后查了后端 `DashScopeTtsService` 代码逻辑，也没问题。最后用诊断脚本 `_diag_pet_tts_logs.py` 拉了服务器日志，发现明确写着 `Pet TTS skipped: no voice mapping for petId=elysia`，而且 `Loaded 4 pet voice mappings`——线上 backend 只有 4 个映射，`elysia` 根本不在里面。
**原因**：`pet-voices.json` 是后端资源文件，打包在 backend 的 jar 里。本地改了源码并 push 了，但服务器上的 backend 容器还是旧版本，没有 `elysia` 映射。只打 Electron 客户端包不够——客户端能把 `petId=elysia` 发给后端，但后端找不到映射就直接跳过 TTS，只返回文字。
**确认方式**：后端日志出现 `Pet TTS skipped: no voice mapping for petId=<角色id>`，且 `Loaded N pet voice mappings` 的 N 不包含新角色。
**解决**：`python scripts/_cloud_deploy_pull.py` 重建 backend。重建后日志变为 `Loaded 5 pet voice mappings`，`elysia` 正常合成。
**教训**：前端改动（petCatalog、desktopSettings、public 资源）需要打 Electron 包；后端改动（pet-voices.json）需要服务器重建容器。两类改动都要做时缺一不可。改了后端资源文件但只打了客户端包，是最容易漏的一步。
**浪费时长**：用户以为是 bug 排查了好几轮，实际只是后端没部署。

### 坑 12：某些动作行 AI 生成崩坏 — 需要逐行修补

**现象**：9 行动作条生成后，大部分角色一致、动作合理，但 `waving`（招手）和 `review`（查看）两行崩坏——角色完全不像爱莉希雅，像是"按体征拼出来的 generic chibi"。其他行（idle、running-right、jumping 等）正常。
**原因**：gpt-image-2 对"挥手"和"检查"这两个动作的语义理解发散更厉害。"挥手"容易让 AI 想到各种夸张的招手姿势，"检查"容易让 AI 加上放大镜、纸等道具，导致角色偏离了 base 的身份。
**解决过程**：
1. 第一轮：收紧 prompt，加上"几乎和 base 一样，只允许一只手从放下到微抬"的约束，重跑 `waving`。重跑后 `waving` 好了。
2. `review` 还是不行，像是"按体征拼出来的 generic chibi"。
3. 第二轮：进一步收紧 `review` prompt，加上"必须仍是 Elysia 本人，不是 generic pink-haired chibi"、"只允许轻微歪头、眨眼或手靠近脸部"、"不换服装、不换发型、不改身体比例"等约束，重跑 `review`。重跑后好了。
**教训**：
- 不要一次性跑完 9 行就走，发现某行崩坏后只修该行，不全套重跑
- 崩坏的行通常是"动作语义发散"导致的，收紧 prompt 到"几乎和 base 一样，只允许极小变化"通常能修好
- 如果一次收紧不够，就再收紧一轮，加更多"不要做什么"的约束
**正确节奏**：base → 用户确认 → 9 行 → 用户看 contact-sheet → 逐行修补 → 重新拼 atlas。每轮修补只改 prompt + 重跑该行 + 重新拼 atlas，不需要重跑其他行。
