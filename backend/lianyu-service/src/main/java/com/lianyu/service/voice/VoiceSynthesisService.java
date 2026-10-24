package com.lianyu.service.voice;

import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.ai.PetVoiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 语音合成门面：**只做参数解析与降级，不做 HTTP**。
 *
 * <p>接替原 {@code DashScopeTtsService} 的位置。拆开的理由是职责本来就两件事：
 *
 * <ul>
 *   <li>「用哪个音色、用哪个 Key、超时多少」——产品配置，留在 Java（本类）</li>
 *   <li>「发请求、拿 URL、下载音频、白名单校验」——AI 能力，在 Python（{@link TtsPort}）</li>
 * </ul>
 *
 * <p>调用方（桌面问候 / 语音通话 / 双工回退）一行都不用改语义：
 * 拿不到音频就是 {@code null}，已有降级路径照旧。
 */
@Slf4j
@Service
public class VoiceSynthesisService {

    private final TtsPort ttsPort;
    private final PetVoiceRegistry petVoiceRegistry;

    @Value("${lianyu.ai.tts.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.ai.tts.api-key:${lianyu.ai.vision.api-key:}}")
    private String apiKey;

    @Value("${lianyu.ai.tts.language-type:Chinese}")
    private String languageType;

    public VoiceSynthesisService(TtsPort ttsPort, PetVoiceRegistry petVoiceRegistry) {
        this.ttsPort = ttsPort;
        this.petVoiceRegistry = petVoiceRegistry;
    }

    /** 官方桌宠音色（平台 Key）。 */
    public TtsPort.SynthesizedAudio synthesizeForPet(String petId, String text) {
        String voice = petVoiceRegistry.resolveHttpVoiceId(petId);
        if (voice == null) {
            log.info("Pet TTS skipped: no voice mapping for petId={}", petId);
            return null;
        }
        return synthesize(voice, null, text, petVoiceRegistry.getModel(), null, petId);
    }

    /**
     * 官方桌宠音色的**流式**合成（语音通话用）。
     *
     * <p>相对 {@link #synthesizeForPet} 只改了"怎么拿音频"：不再等整句合成完，
     * 而是按块回调，让客户端能边收边播。参数解析与降级完全一致。
     */
    public void streamForPet(String petId, String text, TtsPort.TtsChunkSink sink) {
        String voice = petVoiceRegistry.resolveHttpVoiceId(petId);
        if (voice == null) {
            log.info("Pet TTS stream skipped: no voice mapping for petId={}", petId);
            return;
        }
        stream(voice, null, text, petVoiceRegistry.getModel(), null, petId, sink);
    }

    /** 用户自定义音色的流式合成。 */
    public void streamWithVoice(String voiceId, String apiKeyOverride, String text,
                                String httpModel, String apiBaseUrl, TtsPort.TtsChunkSink sink) {
        stream(voiceId, apiKeyOverride, text, httpModel, apiBaseUrl, "custom", sink);
    }

    /** 用户自定义音色（用户 Key + 自定义 base）。 */
    public TtsPort.SynthesizedAudio synthesizeWithVoice(String voiceId, String apiKeyOverride, String text,
                                                        String httpModel, String apiBaseUrl) {
        return synthesize(voiceId, apiKeyOverride, text, httpModel, apiBaseUrl, "custom");
    }

    public TtsPort.SynthesizedAudio synthesizeWithVoice(String voiceId, String apiKeyOverride, String text) {
        return synthesizeWithVoice(voiceId, apiKeyOverride, text, null, null);
    }

    private TtsPort.SynthesizedAudio synthesize(String voiceId,
                                                String apiKeyOverride,
                                                String text,
                                                String httpModelOverride,
                                                String apiBaseUrl,
                                                String logLabel) {
        TtsPort.SynthesizeRequest request = buildRequest(
                voiceId, apiKeyOverride, text, httpModelOverride, apiBaseUrl, logLabel);
        return request == null ? null : ttsPort.synthesize(request);
    }

    private void stream(String voiceId,
                        String apiKeyOverride,
                        String text,
                        String httpModelOverride,
                        String apiBaseUrl,
                        String logLabel,
                        TtsPort.TtsChunkSink sink) {
        if (sink == null) {
            return;
        }
        TtsPort.SynthesizeRequest request = buildRequest(
                voiceId, apiKeyOverride, text, httpModelOverride, apiBaseUrl, logLabel);
        if (request != null) {
            ttsPort.streamSynthesize(request, sink);
        }
    }

    /** 参数解析与准入判断：合成与流式合成共用，避免两条路径的降级口径分叉。 */
    private TtsPort.SynthesizeRequest buildRequest(String voiceId,
                                                   String apiKeyOverride,
                                                   String text,
                                                   String httpModelOverride,
                                                   String apiBaseUrl,
                                                   String logLabel) {
        if (!enabled) {
            log.info("TTS skipped: disabled");
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        if (voiceId == null || voiceId.isBlank()) {
            log.info("TTS skipped: empty voiceId label={}", logLabel);
            return null;
        }
        String key = apiKeyOverride != null && !apiKeyOverride.isBlank()
                ? apiKeyOverride.trim()
                : resolveApiKey();
        if (key == null || key.isBlank()) {
            log.warn("TTS skipped: missing DASHSCOPE API key");
            return null;
        }
        String model = httpModelOverride != null && !httpModelOverride.isBlank()
                ? httpModelOverride.trim()
                : petVoiceRegistry.getModel();
        // 语速：只对配置过倍率的音色生效（pet-voices.json 的 rates），其余走模型默认
        Double rate = petVoiceRegistry.getRate(logLabel);
        return new TtsPort.SynthesizeRequest(
                key, apiBaseUrl, model, voiceId.trim(), text.trim(), languageType, logLabel, rate);
    }

    private String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return System.getenv("DASHSCOPE_API_KEY");
    }
}
