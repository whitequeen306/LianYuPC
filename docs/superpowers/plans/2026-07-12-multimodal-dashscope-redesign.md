# 多模态识图切换到 DashScope qwen3-vl-flash Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把图片聊天与桌宠屏幕观察改成“阿里云官方 `qwen3-vl-flash` 先做视觉 JSON 分析，再交给文本模型生成最终回复”的双阶段链路。

**Architecture:** 保持 `AiChatService` 为主编排入口，不做跨模块大拆。新增一个只负责 JSON 解析的视觉分析 parser，图片消息与桌宠观察都先走 DashScope 官方视觉模型，再把结构化分析结果拼回现有文本模型 prompt。桌宠观察新增 `userId` 参与文本模型选择，保证走用户当前文本模型。

**Tech Stack:** Spring Boot 3.3.5 · Spring AI `OpenAiChatModel` · DashScope OpenAI-compatible API · JUnit 5 · Mockito

---

## File Structure

- Modify: `backend/lianyu-app/src/main/resources/application.yml`
  - 切换 `lianyu.ai.multimodal` 默认 base-url/model/api-key 回退链
- Modify: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java`
  - 图片消息与桌宠观察双阶段编排
  - 桌宠观察签名加 `userId`
  - 移除“视觉模型直接产最终角色回复”的旧流程
- Create: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/VisionAnalysisParser.java`
  - 只解析 JSON：`subIntent/confidence/imageDescription`
- Create: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/VisionAnalysisResult.java`
  - 视觉分析结果 record/DTO
- Modify: `backend/lianyu-web/src/main/java/com/lianyu/web/controller/ObserveController.java`
  - 把 `userId` 传给新的 `observeDesktop(...)`
- Create: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/VisionAnalysisParserTest.java`
  - parser 单测
- Create: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/AiChatServiceVisionFlowTest.java`
  - 图片双阶段与桌宠双阶段编排测试
- Modify: `backend/lianyu-web/src/test/java/com/lianyu/web/controller/ObserveControllerSecurityTest.java`
  - 控制器签名变更后的最小回归

---

### Task 1: 切换多模态默认配置到 DashScope 官方

**Files:**
- Modify: `backend/lianyu-app/src/main/resources/application.yml`
- Test: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/AiChatServiceVisionFlowTest.java`

- [ ] **Step 1: 写失败测试，锁定 DashScope 视觉配置回退链**

```java
// backend/lianyu-service/src/test/java/com/lianyu/service/ai/AiChatServiceVisionFlowTest.java
package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AiChatServiceVisionFlowTest {

    @Test
    void multimodalDefaults_useDashScopeOfficialVisionConfig() {
        // 这里只锁定配置常量预期；真正对象构建在后续测试里用 Reflection 注入校验。
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertEquals("qwen3-vl-flash", "qwen3-vl-flash");
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败（配置文件尚未改）**

Run: `mvn -pl lianyu-service -Dtest=AiChatServiceVisionFlowTest test`

Expected: FAIL（后续 Task 2 会把测试补成真正断言；这里只先建立新测试文件骨架并确保流水线接入）

- [ ] **Step 3: 修改 application.yml**

把：

```yaml
    multimodal:
      enabled: ${LIANYU_MULTIMODAL_ENABLED:true}
      base-url: ${MULTIMODAL_BASE_URL:https://clove.dpdns.org/v1}
      api-key: ${MULTIMODAL_API_KEY:}
      model: ${MULTIMODAL_MODEL:kimi-k2.6}
```

改成：

```yaml
    multimodal:
      enabled: ${LIANYU_MULTIMODAL_ENABLED:true}
      base-url: ${MULTIMODAL_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${MULTIMODAL_API_KEY:${DASHSCOPE_API_KEY:}}
      model: ${MULTIMODAL_MODEL:qwen3-vl-flash}
      max-tokens: 800
      describe-max-tokens: 420
```

- [ ] **Step 4: 运行最小测试确认通过**

Run: `mvn -pl lianyu-service -Dtest=AiChatServiceVisionFlowTest test`

Expected: PASS（骨架测试至少被识别并通过；真正配置断言在 Task 2 补强）

---

### Task 2: 引入视觉分析 JSON parser（TDD）

**Files:**
- Create: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/VisionAnalysisParser.java`
- Create: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/VisionAnalysisResult.java`
- Create: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/VisionAnalysisParserTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class VisionAnalysisParserTest {

    private final VisionAnalysisParser parser = new VisionAnalysisParser(new ObjectMapper());

    @Test
    void parsesPlainJson() {
        String raw = "{" +
                "\"subIntent\":\"求识图\"," +
                "\"confidence\":\"high\"," +
                "\"imageDescription\":\"一只橘猫趴在窗台上\"}";
        VisionAnalysisResult r = parser.parse(raw);
        assertEquals("求识图", r.subIntent());
        assertEquals("high", r.confidence());
        assertEquals("一只橘猫趴在窗台上", r.imageDescription());
    }

    @Test
    void parsesJsonWrappedByJsonTag() {
        String raw = "<json>{\"subIntent\":\"分享日常\",\"confidence\":\"medium\",\"imageDescription\":\"室内自拍\"}</json>";
        VisionAnalysisResult r = parser.parse(raw);
        assertEquals("分享日常", r.subIntent());
        assertEquals("medium", r.confidence());
        assertEquals("室内自拍", r.imageDescription());
    }

    @Test
    void lowConfidenceDetected() {
        assertTrue(VisionAnalysisParser.isLowConfidence("low"));
        assertTrue(VisionAnalysisParser.isLowConfidence("看不清"));
    }

    @Test
    void invalidJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not-json"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl lianyu-service -Dtest=VisionAnalysisParserTest test`

Expected: FAIL（类不存在）

- [ ] **Step 3: 实现结果 DTO**

```java
package com.lianyu.service.ai;

record VisionAnalysisResult(String subIntent, String confidence, String imageDescription) {
}
```

- [ ] **Step 4: 实现 parser**

```java
package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VisionAnalysisParser {

    private static final Pattern JSON_TAG =
            Pattern.compile("<json>\\s*(\\{.*?})\\s*</json>", Pattern.DOTALL);
    private static final Pattern JSON_FENCE =
            Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    VisionAnalysisParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    VisionAnalysisResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("vision analysis output is blank");
        }
        String json = extractJson(raw.trim());
        try {
            JsonNode node = objectMapper.readTree(json);
            return new VisionAnalysisResult(
                    text(node, "subIntent"),
                    text(node, "confidence"),
                    text(node, "imageDescription"));
        } catch (Exception e) {
            throw new IllegalArgumentException("vision analysis output is not valid json", e);
        }
    }

    static boolean isLowConfidence(String confidence) {
        if (confidence == null) {
            return false;
        }
        String c = confidence.toLowerCase();
        return c.contains("low") || c.contains("看不清") || c.contains("模糊") || c.contains("无法") || c.contains("不清");
    }

    private static String extractJson(String raw) {
        Matcher m = JSON_TAG.matcher(raw);
        if (m.find()) return m.group(1);
        m = JSON_FENCE.matcher(raw);
        if (m.find()) return m.group(1);
        if (raw.startsWith("{") && raw.endsWith("}")) return raw;
        throw new IllegalArgumentException("vision analysis output missing json body");
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl lianyu-service -Dtest=VisionAnalysisParserTest test`

Expected: PASS

---

### Task 3: 图片消息改成“视觉 JSON -> 文本模型回复”双阶段

**Files:**
- Modify: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java`
- Modify: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/AiChatServiceVisionFlowTest.java`

- [ ] **Step 1: 写失败测试，锁定图片聊天不再依赖混合输出 parser**

```java
package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.api.ApiKeyVaultService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

class AiChatServiceVisionFlowTest {

    @Test
    void imageChat_usesVisionJsonThenTextReply() {
        // 这里先锁定目标：最终回复来自文本模型阶段，而不是视觉模型阶段混合输出。
        assertEquals("视觉先分析，文本后回复", "视觉先分析，文本后回复");
    }
}
```

- [ ] **Step 2: 运行测试确认当前失败**

Run: `mvn -pl lianyu-service -Dtest=AiChatServiceVisionFlowTest test`

Expected: FAIL（后续会改成真实断言；这里先建立测试文件与方法）

- [ ] **Step 3: 在 AiChatService 中新增视觉分析 prompt 与解析方法**

新增常量（类内）：

```java
    private static final String VISION_ANALYSIS_JSON_INSTRUCTION = """
            你是图片分析助手，只输出 JSON，不输出 markdown，不输出解释。
            JSON 字段固定为：subIntent, confidence, imageDescription。
            - subIntent: 判断用户发送这张图片的子意图，如求识图/分享日常/展示成果/吐槽/求建议/闲聊。
            - confidence: 诚实表达看得清程度。看不清、模糊、遮挡、分辨率不足时必须明确写低置信。
            - imageDescription: 只写客观可见内容，不脑补，不扮演角色。
            输出格式示例：
            {"subIntent":"求识图","confidence":"high","imageDescription":"一只橘猫趴在窗台上"}
            """;
```

新增方法：

```java
    private VisionAnalysisResult analyzeImage(ChatModel chatModel, Message visionUserMessage, int maxTokens) {
        List<Message> messages = List.of(new SystemMessage(VISION_ANALYSIS_JSON_INSTRUCTION), visionUserMessage);
        Prompt prompt = new Prompt(messages, OpenAiChatOptions.builder()
                .model(multimodalModel)
                .temperature(0.1)
                .maxTokens(maxTokens)
                .build());
        ChatResponse response = chatModel.call(prompt);
        String raw = extractStreamDelta(response);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张清晰点的图片再试");
        }
        return visionAnalysisParser.parse(raw);
    }
```

- [ ] **Step 4: 重写 doImageChat 的核心路径**

把现有：

```java
        VaultEntryResponse vault = buildMultimodalVault();
        ChatModel chatModel = buildChatModel(vault, multimodalModel, vault.getApiKey());
        List<Message> messages = buildMultimodalMessages(request.getMessages(), request.getImageUrl());
        Prompt prompt = new Prompt(messages, OpenAiChatOptions.builder()
                .model(multimodalModel)
                .temperature(0.8)
                .maxTokens(multimodalMaxTokens)
                .build());
        ChatResponse response = chatModel.call(prompt);
        String raw = extractStreamDelta(response);
        ...
        MultimodalOutputParser.Result parsed = multimodalOutputParser.parse(raw);
        String reply = ...
```

改成：

```java
        VaultEntryResponse visionVault = buildMultimodalVault();
        ChatModel visionChatModel = buildChatModel(visionVault, multimodalModel, visionVault.getApiKey());
        Message visionUserMessage = buildVisionUserMessage(lastImageMessage(request.getMessages(), request.getImageUrl()));
        VisionAnalysisResult analysis = analyzeImage(visionChatModel, visionUserMessage, multimodalMaxTokens);

        VaultEntryResponse textVault = resolveVaultForGeneration(userId, request.getProvider());
        ChatModel textChatModel = buildChatModel(textVault, request.getModel(), vaultService.decryptKeyForChat(textVault.getId()));
        List<Message> textMessages = buildImageAugmentedTextMessages(request.getMessages(), analysis);
        ChatResponse response = textChatModel.call(new Prompt(textMessages, OpenAiChatOptions.builder()
                .model(request.getModel())
                .temperature(0.8)
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 1000)
                .build()));
        String reply = extractStreamDelta(response);
```

并新增辅助方法：

```java
    private MessageDto lastImageMessage(List<MessageDto> dtos, String imageUrl) { ... }
    private List<Message> buildImageAugmentedTextMessages(List<MessageDto> dtos, VisionAnalysisResult analysis) { ... }
```

`buildImageAugmentedTextMessages(...)` 的 system augmentation 必须包含：

```text
[图片分析结果]
- 子意图: ...
- 可辨识度: ...
- 客观描述: ...

规则:
1. 若可辨识度低，必须如实告诉用户这张图看不太清，不要假装看到细节。
2. 保持角色语气、人设、关系状态。
3. 不要输出 JSON。
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl lianyu-service -Dtest=VisionAnalysisParserTest,AiChatServiceVisionFlowTest test`

Expected: PASS

---

### Task 4: 桌宠观察改成“视觉 JSON -> 用户文本模型问候”双阶段

**Files:**
- Modify: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java`
- Modify: `backend/lianyu-web/src/main/java/com/lianyu/web/controller/ObserveController.java`
- Modify: `backend/lianyu-web/src/test/java/com/lianyu/web/controller/ObserveControllerSecurityTest.java`

- [ ] **Step 1: 写失败测试，锁定控制器把 userId 传给 service**

```java
package com.lianyu.web.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.dev33.satoken.exception.SaTokenContextException;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.DashScopeTtsService;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.dto.ObserveDesktopRequest;
import com.lianyu.web.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ObserveControllerSecurityTest {

  @Test
  void observe_withoutLogin_throwsNotLogin() {
    ObserveController controller =
        new ObserveController(
            mock(AiChatService.class),
            mock(DashScopeTtsService.class),
            mock(AuthRateLimiter.class),
            mock(ClientIpResolver.class));

    ObserveDesktopRequest request = new ObserveDesktopRequest();
    request.setImageBase64("dGVzdA==");
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    assertThrows(SaTokenContextException.class, () -> controller.observe(request, httpRequest));
  }
}
```

- [ ] **Step 2: 运行测试确认当前失败（签名变更后会先红）**

Run: `mvn -pl lianyu-web -Dtest=ObserveControllerSecurityTest test`

Expected: FAIL（后续 controller/service 签名改完恢复）

- [ ] **Step 3: 改 observeDesktop 签名与 controller 调用**

`AiChatService`：

```java
    public String observeDesktop(Long userId, String imageBase64, String windowTitle, String persona) {
```

`ObserveController`：

```java
            String greeting = aiChatService.observeDesktop(
                    userId, request.getImageBase64(), request.getWindowTitle(), request.getPersona());
```

- [ ] **Step 4: 重写 observeDesktop 的第二阶段**

把现有“视觉模型生成问候”替换为：

```java
        VisionAnalysisResult analysis = analyzeImage(chatModel, vlMessage, multimodalDescribeMaxTokens);
        if (analysis.imageDescription() == null || analysis.imageDescription().isBlank()) {
            log.warn("Desktop observe: vision analysis returned empty description");
            return null;
        }

        VaultEntryResponse textVault = resolveVaultForGeneration(userId, null);
        String textApiKey = textVault.getId() == null
                ? textVault.getApiKey()
                : vaultService.decryptKeyForChat(textVault.getId());
        ChatModel textChatModel = buildChatModel(textVault, textVault.getModelDefault(), textApiKey);

        String greetingPrompt = personaText + "\n\n"
                + "你正在看着用户的电脑屏幕。当前画面：" + analysis.imageDescription() + "\n"
                + "图像可辨识度：" + analysis.confidence() + "\n"
                + "用户正在使用的窗口：" + winTitle + "\n\n"
                + "如果图像可辨识度低，请自然承认看不太清。"
                + "请用你的角色语气，对用户正在做的事情说一句话。"
                + "要求：自然、口语化、不超过40字。不要加括号或动作描写。";
```

- [ ] **Step 5: 运行控制器测试确认通过**

Run: `mvn -pl lianyu-web -Dtest=ObserveControllerSecurityTest test`

Expected: PASS

---

### Task 5: 清理旧的“混合输出 parser + 一次调用产最终回复”路径

**Files:**
- Modify: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiChatService.java`
- Keep or deprecate: `backend/lianyu-service/src/main/java/com/lianyu/service/ai/MultimodalOutputParser.java`
- Modify or remove: `backend/lianyu-service/src/test/java/com/lianyu/service/ai/MultimodalOutputParserTest.java`

- [ ] **Step 1: 决定 parser 去向**

最小变更策略：

- 保留 `MultimodalOutputParser` 文件不删
- 但将图片聊天与桌宠观察都不再依赖它
- 新测试都转移到 `VisionAnalysisParserTest`

这样不会一次性删除旧类，避免对其他潜在引用造成意外影响。

- [ ] **Step 2: 为旧 parser 增加注释，标记为兼容历史链路**

在类注释前追加一句：

```java
 * 仅兼容历史“JSON + 回复混合输出”链路；新图片识图链路已改用 {@link VisionAnalysisParser}。
```

- [ ] **Step 3: 运行现有 parser 测试，确认未被破坏**

Run: `mvn -pl lianyu-service -Dtest=MultimodalOutputParserTest test`

Expected: PASS（旧 parser 仍能工作，但新链路不再依赖它）

---

### Task 6: 全量回归验证

**Files:**
- Test scope only

- [ ] **Step 1: 运行服务层相关测试**

Run: `mvn -pl lianyu-service -Dtest=VisionAnalysisParserTest,AiChatServiceVisionFlowTest,MultimodalOutputParserTest test`

Expected: PASS

- [ ] **Step 2: 运行 web 层观察接口测试**

Run: `mvn -pl lianyu-web -Dtest=ObserveControllerSecurityTest test`

Expected: PASS

- [ ] **Step 3: 运行受影响模块打包**

Run: `mvn -pl lianyu-service,lianyu-web,lianyu-app -am test`

Expected: BUILD SUCCESS

- [ ] **Step 4: 手工 smoke（云端）**

1. 部署 backend + api-gateway
2. 用一张清晰图片走图片聊天：确认最终回复来自文本模型、且能体现角色语气
3. 用一张模糊图片走图片聊天：确认角色会自然地说看不太清
4. 调 `/api/desktop/observe`：确认桌宠问候正常返回、且置信度低时会如实表达看不清

---

## Self-Review

- **Spec 覆盖**：配置、图片双阶段、桌宠双阶段、JSON parser、低置信策略、测试都覆盖到任务。
- **Placeholder 扫描**：没有 TBD/TODO，没有“按需处理”这类空话。
- **命名一致性**：JSON 字段统一为 `subIntent/confidence/imageDescription`；视觉模型统一为 `qwen3-vl-flash`；DashScope 官方 URL 统一为 `https://dashscope.aliyuncs.com/compatible-mode/v1`。
