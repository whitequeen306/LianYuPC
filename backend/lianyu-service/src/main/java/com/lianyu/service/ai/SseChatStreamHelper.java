package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.TextUtils;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 下发与收尾的统一出口（chunk/replace/心跳/[DONE]/错误事件）。
 * 供 AiChatService（纯文本流）与 VisionChatService（图片流）共用，保证两条链路
 * 的收尾顺序一致：先 callback.beforeStreamComplete，再 [DONE]，再 complete，最后 onComplete。
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SseChatStreamHelper {

    private final ObjectMapper objectMapper;

    public void sendSseChunk(SseEmitter emitter, String text) throws IOException {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("content", text);
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
    }

    public void sendSseReplace(SseEmitter emitter, String text) throws IOException {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("replace", text);
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
    }

    /**
     * 发送 SSE 心跳注释行（issue #22）：语言门阻塞重生成期间保持连接活跃，
     * 避免代理/客户端因长时间无数据判为空闲超时。注释行不会被 EventSource 解析为数据。
     */
    public void sendSseHeartbeat(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException e) {
            log.debug("SSE heartbeat send failed: {}", e.getMessage());
        }
    }

    public void finishSseSuccess(SseEmitter emitter, String fullContent, AiChatService.StreamCallback callback) {
        try {
            if (callback != null) {
                callback.beforeStreamComplete(emitter, fullContent);
            }
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            log.warn("SSE complete failed", e);
            emitter.complete();
        }
        if (callback != null) {
            callback.onComplete(fullContent, null);
        }
    }

    public void finishSseError(SseEmitter emitter, String message, String partialContent,
                               AiChatService.StreamCallback callback) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("error", message);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (Exception e) {
            log.debug("SSE error event send failed (emitter already completed): {}", e.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
        if (callback != null) {
            Exception err = new BusinessException(ErrorCode.AI_PROVIDER_ERROR, message);
            callback.onComplete(partialContent, err);
        }
    }

    public String resolveStreamErrorMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            String msg = be.getMessage();
            if (msg != null && msg.contains("主机名无法解析")) {
                return "识图服务暂时无法连接（DNS 解析失败），请稍后重试";
            }
            return msg;
        }
        String collected = TextUtils.collectThrowableMessages(e);
        String lower = collected.toLowerCase();
        if (lower.contains("data_inspection_failed") || lower.contains("inappropriate content")) {
            return "图片未能通过内容安全审核，请换一张图片再试";
        }
        if (lower.contains("无法连接") || lower.contains("connection refused")
                || lower.contains("connect timed out") || lower.contains("timed out")
                || lower.contains("unknown host")) {
            return "识图服务暂时无法连接，请稍后重试";
        }
        return collected.isBlank() ? "AI 服务调用失败" : collected;
    }
}
