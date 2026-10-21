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
     * 流式合成：按块回调 PCM，供语音通话边收边播。
     *
     * <p>为什么要单开一个方法而不是把 {@link #synthesize} 改成流式：非实时场景
     * （桌宠问候、冷启动台词）只要「一次拿到完整音频」，改造它纯属增加复杂度；
     * 而通话场景关心的是**首包延迟**，必须按块下发。
     *
     * <p>契约同样是「失败不抛」：拿不到音频就不回调 {@code onChunk}（等价于"没声音"），
     * 中途断流则已回调的块仍然有效。调用方（通话会话）已经按「没音频」降级。
     *
     * @param request 与 {@link #synthesize} 相同的入参
     * @param sink    块回调；**在订阅线程上同步调用**，实现方不要在里面做阻塞操作
     */
    void streamSynthesize(SynthesizeRequest request, TtsChunkSink sink);

    /** 流式合成的接收端。默认方法为空实现，调用方只关心自己需要的那几个。 */
    interface TtsChunkSink {

        /** 收到一块 PCM（s16le / 24kHz / 单声道）。 */
        void onChunk(byte[] pcm);

        /** 正常收完。 */
        default void onDone() {
        }

        /** 上游失败/中断；此时可能已经回调过若干块。 */
        default void onError(String message) {
        }
    }

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
