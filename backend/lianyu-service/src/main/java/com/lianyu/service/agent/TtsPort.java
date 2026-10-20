package com.lianyu.service.agent;

/**
 * 非实时语音合成端口（DashScope multimodal-generation HTTP）。
 *
 * <p>「什么场景该出声、用哪个音色、失败怎么降级」全在 Java；
 * Python 只回答「这段文本用这个音色合成出来的音频字节是什么」。
 *
 * <p><b>失败返回 {@code null}，不抛异常</b> —— 这是原实现的契约，调用方早已按
 * 「没音频」分支优雅降级（桌面问候只是不带音频，语音通话回退成纯文本提示）。
 * 把 null 改成异常会把「可选增强」变成「主链路故障」。
 */
public interface TtsPort {

    SynthesizedAudio synthesize(SynthesizeRequest request);

    /**
     * 一次合成请求。
     *
     * @param apiKey       已解析好的 DashScope Key（用户的或平台配置的）
     * @param baseUrl      用户自定义 base；为空则用官方域名
     * @param model        HTTP 语音模型名（PetVoiceRegistry 或用户配置）
     * @param voiceId      音色 id
     * @param text         待合成文本
     * @param languageType DashScope language_type（默认 Chinese）
     * @param logLabel     仅用于日志区分调用方（pet / custom）
     */
    record SynthesizeRequest(String apiKey,
                             String baseUrl,
                             String model,
                             String voiceId,
                             String text,
                             String languageType,
                             String logLabel) {
    }

    /** 合成结果。音频短（几秒），base64 与字节同时给出，省调用方一次编解码。 */
    record SynthesizedAudio(String mimeType, String base64, byte[] bytes) {

        public static SynthesizedAudio of(String mimeType, byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new SynthesizedAudio(
                    mimeType == null || mimeType.isBlank() ? "audio/wav" : mimeType,
                    java.util.Base64.getEncoder().encodeToString(bytes),
                    bytes);
        }
    }
}
