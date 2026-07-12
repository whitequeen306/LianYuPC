# 多模态识图链路切换到 DashScope qwen3-vl-flash 设计

> **目标：** 把当前“自建中转站 + 单次多模态同时产出 JSON 和角色回复”的图片链路，改成“阿里云官方 `qwen3-vl-flash` 只负责视觉分析，文本模型统一负责最终角色回复”的双阶段链路；桌宠屏幕观察同样切到阿里云官方视觉模型。

**架构：** 视觉分析与最终回复解耦。`qwen3-vl-flash` 通过 DashScope 官方 OpenAI-compatible 接口只输出结构化 JSON；图片聊天与桌宠观察都先走视觉分析，再把分析结果喂给现有文本聊天模型生成最终回复。用户自己的文本模型/vault 不变，人设/记忆/关系/情绪链路继续生效。

**技术栈：** Spring Boot 3.3.5 · Spring AI OpenAI-compatible `OpenAiChatModel` · DashScope 官方 `https://dashscope.aliyuncs.com/compatible-mode/v1` · JUnit 5

---

## 1. 当前问题

现有实现有两条多模态路径，且都依赖 `lianyu.ai.multimodal`：

1. **图片消息链路**
   - `AiChatService#doImageChat` 直接把 system prompt + 历史消息 + 图片发给多模态模型
   - 模型一次调用内同时输出：
     - 结构化 JSON（`sub_intent / confidence / image_description`）
     - 角色回复正文
   - `MultimodalOutputParser` 解析混合输出

2. **桌宠观察链路**
   - `AiChatService#observeDesktop` 先用多模态模型识图
   - 再继续用同一个多模态模型根据 persona 生成桌宠问候

这套做法的问题是：

- 视觉理解与角色回复耦合太紧，识图模型和文本模型职责不清
- 现在的 `multimodal.base-url` 走的是自建中转站 `https://clove.dpdns.org/v1`
- 用户希望改回“识图先分析、文本模型再说话”的传统链路
- 桌宠观察与图片聊天应统一使用阿里云官方视觉模型，而不是中转站

---

## 2. 目标行为

### 2.1 图片消息

当识别到用户发送图片时，后端必须改成两阶段：

1. **阶段一：视觉分析**
   - 使用阿里云官方 `qwen3-vl-flash`
   - 只输出结构化 JSON
   - 不允许输出角色回复、解释、markdown 围栏或额外文本

2. **阶段二：文本回复**
   - 把视觉分析 JSON 结果转成系统补充上下文
   - 再交给用户当前文本模型/vault 产出最终角色回复
   - 角色语气、人设、记忆、关系、情绪链路保持原样

### 2.2 桌宠屏幕观察

当桌宠上传屏幕截图时，后端也改成两阶段：

1. **阶段一：视觉分析**
   - 同样使用阿里云官方 `qwen3-vl-flash`
   - 输出结构化 JSON

2. **阶段二：桌宠问候生成**
   - 使用**用户当前文本模型**（不是平台固定模型）
   - 结合 persona、窗口标题、视觉分析结果生成一句 40 字内问候

### 2.3 结构化 JSON 约束

视觉模型必须只输出这个 schema（英文键已由用户确认）：

```json
{
  "subIntent": "用户发送这张图片的子意图",
  "confidence": "视觉模型对图片可辨识程度的判断",
  "imageDescription": "客观图片描述"
}
```

字段语义：

- `subIntent`
  - 判断用户发图是在求识别、分享日常、展示成果、吐槽、求建议、闲聊等
- `confidence`
  - 必须如实表达是否看得清
  - 看不清、模糊、遮挡、过暗、分辨率不足时必须明确写低置信
- `imageDescription`
  - 只写客观可见内容
  - 不脑补，不扮演角色，不推断不可见事实

---

## 3. 配置设计

当前配置：

```yaml
lianyu:
  ai:
    multimodal:
      base-url: ${MULTIMODAL_BASE_URL:https://clove.dpdns.org/v1}
      api-key: ${MULTIMODAL_API_KEY:}
      model: ${MULTIMODAL_MODEL:kimi-k2.6}
```

改成阿里云官方视觉配置：

```yaml
lianyu:
  ai:
    multimodal:
      enabled: ${LIANYU_MULTIMODAL_ENABLED:true}
      base-url: ${MULTIMODAL_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${MULTIMODAL_API_KEY:${DASHSCOPE_API_KEY:}}
      model: ${MULTIMODAL_MODEL:qwen3-vl-flash}
      max-tokens: 800
      describe-max-tokens: 420
```

设计意图：

- 默认不再走自建中转站
- 默认模型变为 `qwen3-vl-flash`
- 视觉 key 优先用 `MULTIMODAL_API_KEY`
- 若未单独配置，自动回退到现有 `DASHSCOPE_API_KEY`
- URL、模型、key 仍保留 env 覆盖能力

---

## 4. 服务层设计

### 4.1 保持 `AiChatService` 为主编排入口

不引入大规模跨模块重构，仍由 `AiChatService` 负责图片聊天与桌宠观察编排，但会把“视觉分析”从“最终回复生成”中拆出来。

### 4.2 新增视觉分析单元

建议新增一个清晰边界的内部单元，例如：

- `VisionAnalysisParser`
- `VisionAnalysisResult`
- `analyzeImage(...)`

职责：

- 只负责调用 `qwen3-vl-flash`
- 只负责解析 JSON
- 不负责角色回复生成

### 4.3 文本回复仍走现有文本模型

图片聊天最终回复与桌宠问候，都应复用现有文本模型解析路径：

- 图片聊天：使用用户当前聊天模型/vault
- 桌宠观察：也使用用户当前文本模型/vault

这要求把 `observeDesktop(...)` 的签名从：

```java
observeDesktop(String imageBase64, String windowTitle, String persona)
```

改为带 `userId`：

```java
observeDesktop(Long userId, String imageBase64, String windowTitle, String persona)
```

这样桌宠观察阶段二才能 resolve 用户当前文本模型。

---

## 5. 图片消息链路详细设计

### 5.1 阶段一：视觉分析请求

输入：

- `request.getImageUrl()` 对应图片（仍然从 MinIO 取出后转 inline base64）
- 用户当前最后一条消息文本（若有）

Prompt 约束：

- 你是图片分析助手，不扮演角色
- 只输出 JSON
- JSON 必须包含 `subIntent` / `confidence` / `imageDescription`
- 若看不清必须如实写低置信，不允许装作看清
- 不允许输出 markdown 围栏、前后解释或多余文本

输出：

- `VisionAnalysisResult{subIntent, confidence, imageDescription}`

### 5.2 阶段二：文本模型回复

把视觉分析结果追加到现有 system prompt / 上下文中，例如：

```text
[图片分析结果]
- 子意图: ...
- 可辨识度: ...
- 客观描述: ...

规则:
1. 若可辨识度低，必须如实告诉用户这张图看不太清，不要假装看到细节。
2. 仍然保持角色语气与当前关系状态。
3. 不要重复输出结构化 JSON。
```

然后再走原本文本聊天模型，生成最终角色回复。

### 5.3 低置信处理

用户已确认：

- 当 `confidence` 低时，**继续走文本模型**
- 但必须把“如实说看不清”作为硬约束传递给文本模型

也就是说：

- 不做固定回复短路
- 不直接在视觉层终止
- 仍保留角色语气

---

## 6. 桌宠观察链路详细设计

### 6.1 阶段一：视觉分析

对桌面截图调用 `qwen3-vl-flash`，返回 JSON：

- `subIntent`
  - 在桌宠场景里更接近“用户当前正在做什么”的粗分类
- `confidence`
- `imageDescription`

### 6.2 阶段二：文本模型问候

把以下信息传给用户当前文本模型：

- `persona`
- `windowTitle`
- `imageDescription`
- `confidence`

规则：

- 若 `confidence` 低，必须自然表达“这边看不太清”而不是装懂
- 问候仍限制在 40 字以内
- 只输出一句桌宠口语化问候

### 6.3 控制器改动

`ObserveController` 里已有 `userId = StpUtil.getLoginIdAsLong()`，因此只需要把它传给新的：

```java
aiChatService.observeDesktop(userId, request.getImageBase64(), request.getWindowTitle(), request.getPersona())
```

即可。

---

## 7. Parser 设计

当前 `MultimodalOutputParser` 是为“JSON + 回复混合输出”设计的：

- 支持 `<json>...</json>`
- 支持 ```json fence
- 支持裸 `{...}`
- 还能解析 JSON 后面的角色回复正文

新的设计下，视觉模型只应返回纯 JSON，因此 parser 应改成更单一的职责：

### 方案

- 保留 `MultimodalOutputParser` 但重命名/瘦身为 `VisionAnalysisParser`
- 或新建 `VisionAnalysisParser`，让旧 parser 退役

parser 行为：

- 只接受 JSON
- 支持少量兼容（若模型偶尔包 `<json>` / fence）
- 不再承担“回复正文抽取”责任
- 解析失败直接抛业务异常，不再把整段原文当回复回退

原因：

- 旧回退策略适合“混合输出至少还能把整段回复给用户”
- 新链路里视觉结果是机器中间态，不应把脏文本当最终内容继续流转

---

## 8. 错误处理与回退策略

### 图片消息

- 视觉模型调用失败：
  - 返回业务错误：`图片识别失败，请换一张清晰点的图片再试`
- 视觉 JSON 解析失败：
  - 视作视觉调用失败
- 文本模型调用失败：
  - 沿用现有文本聊天错误处理链路

### 桌宠观察

- 视觉模型调用失败：
  - 返回 `null` / 业务失败，控制器仍返回“未能生成问候语”或统一错误
- 文本模型调用失败：
  - 返回 `null` / 业务失败，控制器兜底

### 不做的事

- 不再把中转站作为自动 fallback
- 不再让视觉模型在同一调用内直接产出角色回复

---

## 9. 测试设计

需要补的测试至少包括：

### 9.1 Parser 单测

- 纯 JSON 解析成功
- `<json>` / fenced JSON 兼容成功
- 缺字段 / 空字段处理
- `confidence=low/看不清/模糊` 识别成功
- 非 JSON 输出应失败（不再整体回退为 reply）

### 9.2 服务编排测试

- 图片消息会先调用视觉分析，再调用文本模型
- 文本模型收到的 prompt 中包含 `subIntent/confidence/imageDescription`
- `confidence` 低时仍进入文本模型，但 prompt 中带有“如实说看不清”约束

### 9.3 桌宠观察测试

- `ObserveController` 会把 `userId` 传入 service
- `observeDesktop(userId, ...)` 先视觉分析，再走用户当前文本模型

### 9.4 配置测试

- 默认 URL 为 DashScope 官方 `compatible-mode/v1`
- 默认模型为 `qwen3-vl-flash`
- key 回退链 `MULTIMODAL_API_KEY -> DASHSCOPE_API_KEY`

---

## 10. 非目标

本次不做：

- 不改动纯文本聊天主链路
- 不引入新的前端协议字段
- 不增加视觉结果的数据库持久化
- 不实现 OCR 专项结构化字段（如文字框、坐标）
- 不保留中转站作为自动 fallback

---

## 11. 影响文件（预估）

- `backend/lianyu-app/src/main/resources/application.yml`
- `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java`
- `backend/lianyu-service/src/main/java/com/lianyu/service/ai/MultimodalOutputParser.java`（或替换为新 parser）
- `backend/lianyu-service/src/test/java/com/lianyu/service/ai/MultimodalOutputParserTest.java`
- `backend/lianyu-web/src/main/java/com/lianyu/web/controller/ObserveController.java`
- 可能新增一份视觉分析 DTO/Parser 测试文件

---

## 12. 决策总结

- 识图模型统一切到阿里云官方 `qwen3-vl-flash`
- 视觉调用统一走 DashScope 官方 API，不再走自建中转站
- 图片聊天改成“两阶段”：视觉 JSON -> 文本模型回复
- 桌宠观察也改成“两阶段”：视觉 JSON -> 用户当前文本模型问候
- JSON 字段固定为：`subIntent` / `confidence` / `imageDescription`
- `confidence` 低时继续走文本模型，但强制其如实表达“看不太清”
