# Agent 操控电脑：业界做法调研与我们的方案

> 2026-10 调研。目的：回答「为什么自研能力层在网易云这种应用上点不准」，并给出一套可落地的架构。
> 结论先行：**纯坐标猜测不可行；确定性来自「结构化元素树」与「编号化视觉标记（Set-of-Mark）」两者融合**，
> 模型只负责"选编号"，坐标由客户端自己映射。

---

## 1. 四种范式与代表工作

| 范式 | 代表 | 做法 | 关键数字 | 我们的评价 |
|---|---|---|---|---|
| **A. 纯视觉坐标** | Anthropic Computer Use、OpenAI Operator 早期 | 截图 → 模型直接吐 x,y → 点击 | 模型"猜坐标"能力弱；GPT-4V 在 ScreenSpot 桌面仅 **20.2%（文本）/11.8%（图标）** | ❌ 最不可靠。我们 v0.3.9 的失败就属于这一类（见 §3） |
| **B. SoM 编号视觉** | [OmniParser](https://arxiv.org/html/2408.00203v1)（微软）、[WindowsAgentArena](https://arxiv.org/html/2409.08264v1) 的 Navi | 截图叠加**带编号的框** → 模型只回编号 → 客户端点框中心 | OmniParser 把 GPT-4V 的"选对元素"能力从 **0.705 → 0.938**；ScreenSpot 桌面文本 **91.3%**、图标/控件 **63.6%** | ✅ 通用兜底，任何界面都能用；缺点是每步一次 VLM |
| **C. 无障碍树（UIA/MSAA）** | [UFO](https://arxiv.org/html/2402.07939v5)（微软）、FlaUI/pywinauto 生态 | 遍历控件树 → 拿 `{名字, 角色, bbox}` → 按 id 点 | 确定性最好、可零成本复用；但**自绘/CEF 应用常常为空** | ✅ 首选；需要兜底 |
| **D. 混合融合（C+B）+ GUI–API 混合动作** | [UFO²: The Desktop AgentOS](https://arxiv.org/html/2504.14603v1) | UIA 层检测 + 视觉层检测 → **融合去重** → 统一动作层；HostAgent 拆任务、AppAgent 按应用执行；知识沉淀 + 投机多动作执行 | 20+ 真实 Windows 应用上显著优于纯视觉 CUA，**比 Operator 高 10%+**（即使只用 GPT-4o） | ✅✅ 与我们的架构（Electron 客户端执行 + Python 决策）最契合 |

**冷静的基线**：WindowsAgentArena 上最好的开源多模态 agent 只有 **19.5%** 成功率，人类是 **74.5%**
（[项目主页](https://microsoft.github.io/WindowsAgentArena/)）。所以"能放歌"这种事，必须靠**确定性元素/配方**，
不能指望模型每次都推理对。

---

## 2. 三个被反复验证的工程结论

1. **不要让模型输出坐标**。SoM 的意义就是"把坐标问题降级成选择题"：编号 → 框 → 点框中心。
   OmniParser 的消融显示，同样的 GPT-4V，加了编号与局部语义后准确率从 0.705 涨到 0.938。
2. **局部语义（local semantics）几乎和编号一样重要**：每个框配一句"这是什么控件/什么文字"，
   模型才不会把"大海 - 张雨生"和"大海 (Live)"搞混。
3. **动作之后必须校验**。UFO² 的"投机多动作执行"依赖轻量控制状态检查；没有校验的 agent 会一直盲点。

---

## 3. 我们现在的实现与实测差距

当前链路：模型（Python）→ Java `/internal/agent/desktop/exec` → STOMP → Electron `capabilityHost`
→ PowerShell 常驻 actor（内嵌 C#）：

| 能力 | 实现手段 |
|---|---|
| 窗口/前台 | `EnumWindows` / `GetForegroundWindow` |
| 切前台 | `SetForegroundWindow` |
| **元素树** | **UIA（`AutomationElement`）** 遍历前台窗口子树 |
| 点击/按键 | `SendInput`（鼠标/键盘） |
| 打字 | 短文本 Unicode `SendInput`；长文本走剪贴板 + Ctrl+V |
| 截图 | Electron `desktopCapturer` → JPEG → **只喂视觉模型做摘要，不进文本上下文** |

**实测（本机 actor 直连）**：

```
记事本      elements = 52   ✅
网易云音乐  elements = 0    ❌（CEF/Chromium，无障碍树默认不构建）
```

网易云 UIA=0 → 模型拿不到 `element_id`；而 `desk_screen_read` 返回的是**文字摘要，没有坐标** →
模型只能猜 x/y → `SendInput` 机械成功、实际点空。
这解释了审计表的特征：**8 次调用全 ok、零错误码，但任务没完成**（接地失败，不是链路失败）。

---

## 4. 建议方案：三层感知 + 编号接地 + 动作校验

### 4.1 感知层（每次观察产出统一的 "Observation"）

```
① 无障碍树（首选，零 VLM 成本）
   UIA FromHandle(前台窗口) → 子元素遍历
   ├─ 命中 0 个 且 目标是 Chromium/Electron？
   │   ├─ 触发无障碍：向窗口发 WM_GETOBJECT / 用 UIA FromPoint 探一次
   │   └─ 仍为空 → 退 MSAA：AccessibleObjectFromWindow(hwnd, OBJID_CLIENT) 走 IAccessible 树
   └─ 仍为空 → 标记 "tree_unavailable"

② 视觉检测（tree_unavailable 或需要图标语义时）
   截图 → 交互区域检测（框） + OCR 文本 + 图标描述
   → 与①的结果**融合去重**（同名同位置合并；OCR 命中优先用文本）

③ 产出 Observation = 编号元素列表
   [{id, source: uia|vision, role, label, bbox, confidence}]
```

> 实现取舍：①用我们已有的 C# actor 扩展（UIA → MSAA 兜底）即可，无新依赖；
> ② 不必上 OmniParser 全套模型——先用「OCR + 我们现有 VLM 出框」起步，编号来自我们自己的融合结果。

### 4.2 接地层（grounding）：模型只选编号

- 工具面统一为 `desk_observe` → 返回编号清单（+ 截图给视觉模型看的 SoM 图，可选）；
- `desk_click(target)` 的 `target` **只接受编号**（或"文字"由客户端在本清单里做包含匹配）；
- **硬规则：没有 Observation 编号就不允许坐标点击**（policy 层拒绝），彻底消灭"猜坐标"。
- 坐标换算（编号→屏幕物理像素）放在 Electron，模型永远不接触坐标 → 顺带解决多屏/DPI 缩放。

### 4.3 动作层：GUI + API 双通道

| 场景 | 首选 |
|---|---|
| 播放/暂停/音量/静音 | `desk_system_control`（媒体键 API）——比点界面快且稳 |
| 打开应用/文件 | Shell 协议 + `appIndex`（已有） |
| 其余（点、输入） | SendInput（用 bbox 中心） |

这正是 UFO² 的 "GUI–API 混合动作层"：能用 API 就别点像素。

### 4.4 校验层：每步三信号 + 一次换策略重试

动作后取三个廉价信号：
1. 前台窗口标题/进程是否变化；
2. 元素数量与关键元素 diff（编号集合的对称差）；
3. 截图像素差（低分辨率缩略图哈希即可）。

判定：
- 有变化 → 继续；
- 无变化 → **换策略重试一次**（编号点击失败 → 该元素改用 `Invoke` 模式；
  再失败 → 改用视觉编号；再失败 → 如实告诉用户"点了但没反应"），不允许静默重试。

### 4.5 知识层：按应用缓存"配方"

第一次成功的动作序列按 `(进程名, 窗口标题模式)` 存成配方：
`启动网易云 → 点"搜索"输入框 → 输入 {q} → 等 300ms → 点列表首项`。
第二次同一诉求直接走确定性快路径（0 次 VLM），失败再退回 4.1–4.4。
这就是 UFO² "Continuous Knowledge Integration" 的轻量版，也是"老 AgentAssistant 四步能放歌"的本质。

---

## 5. 分阶段落地与验收

| 阶段 | 内容 | 验收（真机） |
|---|---|---|
| **P0**（必须先做） | ① MSAA/IAccessible 兜底 + 触发 Chromium 无障碍；② SoM 编号接地（模型只选编号，坐标由客户端映射）；③ policy 禁止无编号坐标点击 | 网易云：能拿到 ≥1 个可交互元素；"打开网易云→搜大海→点第一首"真机跑通 |
| **P1** | 动作后三信号校验 + 换策略重试；进度/失败如实上报 | 点空白/点错时能在 1 次内纠正；失败文案准确 |
| **P2** | 应用配方缓存；投机多动作（一次规划 2–3 步，逐步校验） | 同一任务第二次执行：VLM 帧数下降 ≥50%，步数下降 ≥30% |

预算参考：树命中时每步 ≈ 50–300ms、0 帧 VLM；视觉兜底每步 ≈ 1–3s、1 帧。
按"树优先"策略，真实任务里 VLM 帧数可从"每步 1 帧"降到"每任务 1–2 帧"。

---

## 6. 明确不做 / 暂不做

- **PiP 虚拟桌面隔离**（UFO² 用 RDP loopback 做并发不打扰）：工程量大，且与"陪伴类产品"的调性不符；
  我们用 R2/R3 确认 + Esc 中止 + 审计来控风险。
- **接入第三方 GUI 专用模型权重**（UI-TARS/OS-Atlas 本地推理）：客户端不能背几百 MB 权重，
  且自有 VLM 已能做摘要；先用"OCR + 现有 VLM 出框"落地，效果不够再评估。
- **纯视觉坐标点击**：永不作为主路径。

---

## 7. 参考资料

- [UFO: A UI-Focused Agent for Windows OS Interaction](https://arxiv.org/html/2402.07939v5) —— UIA + 截图，双 agent（Host/App）
- [UFO²: The Desktop AgentOS](https://arxiv.org/html/2504.14603v1) —— 混合控制检测（UIA × 视觉融合去重）、GUI–API 动作层、知识沉淀、投机多动作、PiP
- [OmniParser for Pure Vision Based GUI Agent](https://arxiv.org/html/2408.00203v1) —— SoM 编号、局部语义、ScreenSpot 数据（桌面文本 91.3%）
- [Windows Agent Arena](https://arxiv.org/html/2409.08264v1) / [项目主页](https://microsoft.github.io/WindowsAgentArena/) —— 基准：SoM agent 19.5%，人类 74.5%
- [UI-TARS](https://github.com/bytedance/ui-tars) —— 端到端 GUI 模型（接地 + 动作，归一化坐标）
- [MSAA 与 UI Automation 对比](https://learn.microsoft.com/en-us/windows/win32/winauto/microsoft-active-accessibility-and-ui-automation-compared)、[WM_GETOBJECT](https://learn.microsoft.com/en-us/windows/win32/winauto/wm-getobject) —— 无障碍树的技术底座
- [Chrome 无障碍树与屏幕阅读器检测](https://issues.chromium.org/41412449) —— Chromium 只在"检测到辅助技术"时才构建无障碍树（`--force-renderer-accessibility` 可强制）
