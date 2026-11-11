package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.entity.CharacterState;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.UserCustomVoice;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.agent.AgentBlockingPort;
import com.lianyu.service.agent.AgentChatResult;
import com.lianyu.service.agent.AgentTextPort;
import com.lianyu.service.agent.AgentTextStreamPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.agent.VoiceCuePort;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.ai.PetVoiceRegistry;
import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.dto.VoiceCallEndRequest;
import com.lianyu.service.dto.VoiceCallTurnResponse;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.graph.MessageModelContent;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.voice.CustomVoiceProviders;
import com.lianyu.service.voice.CustomVoiceService;
import com.lianyu.service.voice.SentenceSplitter;
import com.lianyu.service.voice.VoiceCallTarget;
import com.lianyu.service.voice.VoiceProsody;
import com.lianyu.service.voice.VoiceSynthesisService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceCallService {

    private static final String SEQ_KEY_PREFIX = "msg_seq:";
    private static final String VOICE_CALL_TURN_MARKER = "system/voice-call-turn";
    private static final String VOICE_CALL_SUMMARY_MARKER = "system/voice-call-summary";
    /** 主动来电邀请：只发卡片，不自动开麦（P3 可选，默认关）。 */
    private static final String VOICE_CALL_INVITE_MARKER = "system/voice-call-invite";
    private static final String CALL_INVITE_KEY_PREFIX = "voice-invite:";
    /**
     * Voice-call allowlist: must have realtimeVoices mapping (HTTP fallback uses voices).
     * Keep in sync with frontend {@code voiceCallPets.js}.
     * <p>
     * 2026-11-09 补 kafka：实时模型退役后通话本来就走 HTTP 逐句合成，
     * 只要音色注册过（{@code hasVoice}）就够用；漏登记会让广场新增的角色
     * 既看不到入口、直接调接口还会 403「当前角色暂不支持语音通话」。
     */
    private static final Set<String> VOICE_CALL_PET_IDS = Set.of(
            "klee",
            "ganyu",
            "ayaka",
            "raiden",
            "elysia",
            "erii_uesugi",
            "yae_miko",
            "kokomi",
            "shenhe",
            "nahida",
            "hu_tao",
            "furina",
            "noelle",
            "kafka"
    );

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final CharacterSquareTemplateMapper squareTemplateMapper;
    private final AsrService asrService;
    private final PetVoiceRegistry petVoiceRegistry;
    private final VoiceSynthesisService voiceSynthesisService;
    private final AgentBlockingPort agentBlockingPort;
    private final AgentTextStreamPort agentTextStreamPort;
    private final AgentTextPort agentTextPort;
    private final ApiKeyVaultService apiKeyVaultService;
    private final ChatTurnFacade chatTurnFacade;
    private final MemoryWriter memoryWriter;
    private final StringRedisTemplate redisTemplate;
    private final AiBackgroundPublisher aiBackgroundPublisher;
    private final CustomVoiceService customVoiceService;
    private final FileStorageService fileStorageService;
    private final VoiceCuePort voiceCuePort;
    private final CharacterStateService characterStateService;
    private final com.lianyu.service.agent.AgentMemoryPort memoryPort;
    private final com.lianyu.service.relationship.RelationshipStateService relationshipStateService;

    @Value("${lianyu.voice-call.max-reply-chars:48}")
    private int maxReplyChars;

    /**
     * P2：通话回合是否带入关系上下文 + 上一通摘要。默认关，部署侧按验收结果打开。
     *
     * <p>关系块由 Python 组装（`_SCENES_RELATIONSHIP` 已含 VOICE_CALL），Java 只把
     * 「这次要不要带」这个事实传给 prompt 层。
     */
    @Value("${lianyu.voice-call.relationship-context.enabled:false}")
    private boolean relationshipContextEnabled;

    /**
     * P3：通话中的轻量记忆写入（只写 episode）。默认关。
     *
     * <p>要不要记、记成什么由 Python 判定；Java 只负责限频后把这一轮的原话递过去。
     */
    @Value("${lianyu.voice-call.memory-moment.enabled:false}")
    private boolean memoryMomentEnabled;

    /**
     * P3：主动来电邀请（只落一张邀请卡片，点开才进通话）。默认关。
     *
     * <p>打扰类能力，条件从严：关系阶段过 TESTING、非免打扰、距上次通话 ≥
     * {@link #callInviteMinGapHours} 小时、每个角色每周最多一次。
     */
    @Value("${lianyu.voice-call.call-invite.enabled:false}")
    private boolean callInviteEnabled;

    @Value("${lianyu.voice-call.call-invite.min-gap-hours:72}")
    private long callInviteMinGapHours;

    /**
     * P3：挂断语音留言（角色挂断后补一句可播放的话）。默认关。
     *
     * <p>需要「生成文案 → 合成 → 上传」三步，全部异步，失败只少一条留言。
     */
    @Value("${lianyu.voice-call.voicemail.enabled:false}")
    private boolean voicemailEnabled;

    /**
     * 本通通话内最多带入 LLM 的历史句数（user+assistant 各算一句）。
     * 仅当前通话有效；挂断摘要写入后，旧 turn 不再参与任何上下文。
     */
    @Value("${lianyu.voice-call.history-limit:8}")
    private int historyLimit;

    @Value("${lianyu.voice-call.max-tokens:512}")
    private int maxTokens;

    // 故意不加 @Transactional：ASR + LLM + TTS 三段外部慢调用全在这里，
    // 进事务会把 Hikari 连接和行锁占住，上游一挂全场卡死。
    // 只在最后一步「防重 + 落两条消息」进事务，缩小锁持有窗口。
    public VoiceCallTurnResponse processTurn(Long userId, Long conversationId, MultipartFile audio) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持单聊语音通话");
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);

        VoiceCallTarget target = resolveCallTarget(userId, character);
        if (target == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色暂不支持语音通话");
        }
        if (target.isLocal()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地语音模型请使用桌面端实时通话");
        }

        long t0 = System.nanoTime();
        String userText = asrService.transcribe(audio);
        long tAsr = System.nanoTime();
        if (userText == null || userText.isBlank()) {
            // 持续通话会切静音片段；空识别不算错误，由前端跳过本轮
            return VoiceCallTurnResponse.builder().userText("").replyText("").build();
        }
        userText = UserInputSanitizer.sanitizeChatMessage(userText).storedText();
        if (userText.isBlank()) {
            return VoiceCallTurnResponse.builder().userText("").replyText("").build();
        }

        // 通话内容不进关系/情绪/记忆；唯一持久作用是挂断时摘要进文字聊
        int msgLimit = clampHistoryMessageLimit(historyLimit);
        List<Message> history = recentVoiceCallTurnsInCurrentCall(conversationId, msgLimit);
        history = trimCurrentUserTurn(history, userText);

        String replyText = generateShortVoiceReply(userId, conversationId, character, history, userText);
        long tLlm = System.nanoTime();
        if (replyText.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "角色暂时无法回复，请稍后再试");
        }

        TtsPort.SynthesizedAudio audioOut = synthesizeForTarget(target, replyText);
        long tTts = System.nanoTime();
        if (audioOut == null || audioOut.base64() == null || audioOut.base64().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音合成失败，请稍后再试");
        }

        Message[] persisted = persistVoiceTurn(conversationId, character.getId(), userText, replyText);
        Message userMsg = persisted[0];
        Message assistantMsg = persisted[1];

        log.info("Voice call turn: convId={}, mode={}, petId={}, userLen={}, replyLen={}, asrMs={}, llmMs={}, ttsMs={}",
                conversationId, target.mode(), target.petId(), userText.length(), replyText.length(),
                (tAsr - t0) / 1_000_000L, (tLlm - tAsr) / 1_000_000L, (tTts - tLlm) / 1_000_000L);

        return VoiceCallTurnResponse.builder()
                .userText(userText)
                .replyText(replyText)
                .audioBase64(audioOut.base64())
                .audioMimeType(audioOut.mimeType())
                .userMessageId(userMsg.getId())
                .replyMessageId(assistantMsg.getId())
                .build();
    }

    /** 仅最后一步落库进事务：用户句 + 角色句，原子写入。 */
    @Transactional
    protected Message[] persistVoiceTurn(Long conversationId, Long characterId, String userText, String replyText) {
        long userSeq = nextSeq(conversationId);
        Message userMsg = new Message();
        userMsg.setSeq(userSeq);
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setCharacterId(characterId);
        userMsg.setContent(userText);
        userMsg.setAudioUrl("system/voice-call-turn");
        messageMapper.insert(userMsg);

        long assistantSeq = nextSeq(conversationId);
        Message assistantMsg = new Message();
        assistantMsg.setSeq(assistantSeq);
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setCharacterId(characterId);
        assistantMsg.setContent(replyText);
        assistantMsg.setAudioUrl("system/voice-call-turn");
        messageMapper.insert(assistantMsg);
        return new Message[]{userMsg, assistantMsg};
    }

    /** Duplex: assert ownership + supported voice target. */
    public VoiceCallTarget resolveAndAssertCallTarget(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持单聊语音通话");
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);
        VoiceCallTarget target = resolveCallTarget(userId, character);
        if (target == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色暂不支持语音通话");
        }
        return target;
    }

    /** @deprecated prefer {@link #resolveAndAssertCallTarget} */
    public String resolveAndAssertCallPet(Long userId, Long conversationId) {
        VoiceCallTarget target = resolveAndAssertCallTarget(userId, conversationId);
        return target.petId();
    }

    /**
     * Official pet allowlist first (never mix with custom storage); else READY custom voice.
     */
    public VoiceCallTarget resolveCallTarget(Long userId, Character character) {
        if (character == null) {
            return null;
        }
        String petId = resolveVoicePetId(character);
        if (petId != null && VOICE_CALL_PET_IDS.contains(petId)
                && (petVoiceRegistry.hasRealtimeVoice(petId) || petVoiceRegistry.hasVoice(petId))) {
            return new VoiceCallTarget(
                    VoiceCallTarget.Mode.OFFICIAL_PET,
                    petId,
                    petVoiceRegistry.resolveHttpVoiceId(petId),
                    petVoiceRegistry.resolveRealtimeVoiceId(petId),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        UserCustomVoice custom = customVoiceService.findReady(userId, character.getId());
        if (custom == null) {
            return null;
        }
        if (CustomVoiceProviders.DASHSCOPE_VC.equalsIgnoreCase(custom.getProvider())
                && custom.getRealtimeVoiceId() != null && !custom.getRealtimeVoiceId().isBlank()) {
            return new VoiceCallTarget(
                    VoiceCallTarget.Mode.CUSTOM_DASHSCOPE,
                    null,
                    custom.getHttpVoiceId(),
                    custom.getRealtimeVoiceId(),
                    customVoiceService.decryptApiKey(custom),
                    null,
                    null,
                    custom.getEndpoint(),
                    custom.getHttpModel(),
                    custom.getRealtimeModel());
        }
        if (CustomVoiceProviders.GPTSOVITS_LOCAL.equalsIgnoreCase(custom.getProvider())
                && custom.getEndpoint() != null && custom.getRefText() != null
                && custom.getRefAudioObjectKey() != null) {
            return new VoiceCallTarget(
                    VoiceCallTarget.Mode.CUSTOM_LOCAL,
                    null,
                    null,
                    null,
                    null,
                    fileStorageService.resolvePublicUrl(custom.getRefAudioObjectKey()),
                    custom.getRefText(),
                    custom.getEndpoint(),
                    null,
                    null);
        }
        return null;
    }

    private TtsPort.SynthesizedAudio synthesizeForTarget(VoiceCallTarget target, String text) {
        if (target == null) {
            return null;
        }
        if (target.mode() == VoiceCallTarget.Mode.CUSTOM_DASHSCOPE) {
            return voiceSynthesisService.synthesizeWithVoice(
                    target.httpVoiceId() != null ? target.httpVoiceId() : target.realtimeVoiceId(),
                    target.apiKey(),
                    text,
                    target.httpModel(),
                    target.endpoint());
        }
        if (target.mode() == VoiceCallTarget.Mode.OFFICIAL_PET) {
            return voiceSynthesisService.synthesizeForPet(target.petId(), text);
        }
        return null;
    }

    /**
     * 与 {@link #synthesizeForTarget} 同源，但按块回调 —— 语音通话用它边收边播。
     *
     * <p>两者共用同一套目标解析与准入判断（`buildRequest`），避免「整句能用、流式不能用」
     * 这类分叉。本地 sovits 目标不走这里（它有独立的端点与协议）。
     */
    public void streamSynthesizeForTarget(VoiceCallTarget target, String text,
                                          TtsPort.TtsChunkSink sink) {
        streamSynthesizeForTarget(target, text, VoiceProsody.NEUTRAL, sink);
    }

    /**
     * 带情绪韵律的流式合成。
     *
     * <p>只有官方音色吃语速倍率；自定义音色的参数是用户自己的资产，不替用户改。
     * 韵律的数值映射在 {@link VoiceProsody}，这里只负责往下传。
     */
    public void streamSynthesizeForTarget(VoiceCallTarget target, String text,
                                          VoiceProsody prosody,
                                          TtsPort.TtsChunkSink sink) {
        if (target == null || sink == null) {
            return;
        }
        double rate = prosody == null ? 1.0 : prosody.rate();
        if (target.mode() == VoiceCallTarget.Mode.CUSTOM_DASHSCOPE) {
            voiceSynthesisService.streamWithVoice(
                    target.httpVoiceId() != null ? target.httpVoiceId() : target.realtimeVoiceId(),
                    target.apiKey(),
                    text,
                    target.httpModel(),
                    target.endpoint(),
                    sink);
            return;
        }
        if (target.mode() == VoiceCallTarget.Mode.OFFICIAL_PET) {
            voiceSynthesisService.streamForPet(target.petId(), text, rate, sink);
        }
    }

    /**
     * 取「此刻该用什么声音」：情绪名与强度来自角色状态（事实），映射成数值在 {@link VoiceProsody}。
     *
     * <p>任何异常都退回中性韵律 —— 语调只是锦上添花，不能因为一次查询失败让整句没声。
     */
    public VoiceProsody resolveProsody(Long userId, Long conversationId) {
        try {
            Conversation conversation = findOwned(userId, conversationId);
            CharacterState state = characterStateService.getOrCreate(conversation.getCharacterId(), userId);
            if (state == null) {
                return VoiceProsody.NEUTRAL;
            }
            int intensity = state.getEmotionIntensity() == null ? 50 : state.getEmotionIntensity();
            return VoiceProsody.resolve(state.getCurrentEmotion(), intensity);
        } catch (Exception e) {
            log.debug("Voice prosody fallback conv={}: {}", conversationId, e.toString());
            return VoiceProsody.NEUTRAL;
        }
    }

    /**
     * 通话中轻量写记忆（P3）：只写 episode（带用户原话），不写长期事实。
     *
     * <p>限频（每 3 轮 ≤1）与字数门槛在调用方；这里只管「开关 → 取 characterId →
     * 异步交给 agent」。异步是因为这是一次外部 HTTP + 一次模型判定，
     * 让它占住回合线程只会让下一句回复变慢。
     */
    public void recordVoiceMoment(Long userId, Long conversationId, String userText, String replyText) {
        if (!memoryMomentEnabled || memoryPort == null || userId == null || conversationId == null) {
            return;
        }
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Conversation conversation = findOwned(userId, conversationId);
                Long characterId = conversation.getCharacterId();
                if (characterId == null) {
                    return;
                }
                memoryPort.writeVoiceMomentIfActive(
                        userId, conversationId, characterId, userText, replyText);
            } catch (Exception e) {
                log.debug("Voice moment skipped conv={}: {}", conversationId, e.toString());
            }
        });
    }

    @Transactional
    public void persistUserTurn(Long userId, Long conversationId, String userText) {
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        long userSeq = nextSeq(conversationId);
        Message userMsg = new Message();
        userMsg.setSeq(userSeq);
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setCharacterId(character.getId());
        userMsg.setContent(userText);
        userMsg.setAudioUrl("system/voice-call-turn");
        messageMapper.insert(userMsg);
        // 不写关系/情绪/记忆：通话句只服务本通 LLM，挂断摘要才进文字聊
    }

    @Transactional
    public void persistAssistantTurn(Long userId, Long conversationId, String replyText) {
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        long assistantSeq = nextSeq(conversationId);
        Message assistantMsg = new Message();
        assistantMsg.setSeq(assistantSeq);
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setCharacterId(character.getId());
        assistantMsg.setContent(replyText);
        assistantMsg.setAudioUrl("system/voice-call-turn");
        messageMapper.insert(assistantMsg);
        // 不 enqueue 会话记忆；挂断 endCall 时再写摘要进文字聊
    }

    public AiChatRequest buildVoiceCallAiRequest(Long userId, Long conversationId, String userText) {
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        VaultEntryResponse userVault = apiKeyVaultService.resolvePreferredUserVault(userId);
        if (userVault == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "未配置文本模型，请在设置中添加");
        }
        int msgLimit = clampHistoryMessageLimit(historyLimit);
        List<Message> history = recentVoiceCallTurnsInCurrentCall(conversationId, msgLimit);
        history = trimCurrentUserTurn(history, userText);
        history = withPreviousCallSummary(conversationId, history);
        return buildVoiceAiRequest(userId, conversationId, character, history, userText, userVault);
    }

    /**
     * 把上一通电话的摘要插到历史最前面，让这一通能接上上次的话题。
     *
     * <p>只取最近一条、且限制在 7 天内：更久以前的通话细节角色本来也不该记得那么清，
     * 塞进去只会挤占本通的热上下文。开关关着时完全不动历史（保持 P1 行为）。
     */
    private List<Message> withPreviousCallSummary(Long conversationId, List<Message> history) {
        if (!relationshipContextEnabled || history == null) {
            return history;
        }
        try {
            Message summary = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .eq(Message::getAudioUrl, VOICE_CALL_SUMMARY_MARKER)
                    .orderByDesc(Message::getSeq)
                    .last("LIMIT 1"));
            if (summary == null || summary.getId() == null) {
                return history;
            }
            if (summary.getCreatedAt() != null
                    && summary.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusDays(7))) {
                return history;
            }
            List<Message> merged = new ArrayList<>(history.size() + 1);
            merged.add(summary);
            merged.addAll(history);
            return merged;
        } catch (Exception e) {
            log.debug("Voice call last-summary lookup skipped conv={}: {}", conversationId, e.toString());
            return history;
        }
    }

    public java.util.concurrent.CompletableFuture<String> streamVoiceReply(
            Long userId,
            AiChatRequest aiRequest,
            java.util.function.Consumer<String> onDelta) {
        String system = "";
        List<MessageDto> messages = aiRequest.getMessages();
        if (messages != null && !messages.isEmpty()
                && "system".equalsIgnoreCase(messages.get(0).getRole())) {
            system = messages.get(0).getContent();
            messages = messages.subList(1, messages.size());
        }
        return agentTextStreamPort.stream(
                userId, system, messages, aiRequest.getProvider(), aiRequest.getModel(),
                aiRequest.getExpectedLanguage(), onDelta);
    }

    public String clampReply(String spoken) {
        if (spoken == null) {
            return "";
        }
        if (spoken.length() > maxReplyChars) {
            return spoken.substring(0, maxReplyChars).trim();
        }
        return spoken;
    }

    /**
     * 语音通话开场/冷场台词（P1 只接 {@code opening}）。
     *
     * <p>Java 只装配事实（谁、哪个会话、什么场景）；文案与模型调用在 Python。
     * 任何失败都返回 null —— 开场生成不出来只损失一句问候，不能影响整通电话。
     */
    public String generateVoiceCue(Long userId, Long conversationId, String kind) {
        if (voiceCuePort == null || userId == null || conversationId == null) {
            return null;
        }
        try {
            Conversation conversation = findOwned(userId, conversationId);
            Character character = characterMapper.selectById(conversation.getCharacterId());
            if (character == null) {
                return null;
            }
            String text = voiceCuePort.generateCue(
                    userId, character.getId(), conversationId, character.getName(),
                    character.getSettings(), kind);
            return text == null || text.isBlank() ? null : text.trim();
        } catch (Exception e) {
            log.debug("Voice cue skipped conv={} kind={}: {}", conversationId, kind, e.toString());
            return null;
        }
    }

    /**
     * 主动来电邀请（P3 可选）：全部条件都满足时落一张邀请卡片并返回它；否则返回 null。
     *
     * <p>只发卡片，不自动开麦 —— 用户点了才进通话。任一条不满足就静默退回普通主动文字，
     * 不解释也不重试（打扰类能力宁可少发）。
     */
    public MessageResponse trySendCallInvite(Long userId, Long conversationId, Character character) {
        if (!callInviteEnabled || userId == null || conversationId == null || character == null) {
            return null;
        }
        if (resolveVoicePetId(character) == null) {
            return null;
        }
        if (com.lianyu.service.character.CharacterPreferenceResolver.isDoNotDisturbActive(character)) {
            return null;
        }
        try {
            com.lianyu.service.relationship.RelationshipSnapshot snapshot =
                    relationshipStateService.getSnapshot(userId, character.getId());
            if (snapshot == null
                    || snapshot.phase() == com.lianyu.service.relationship.RelationshipPhase.TESTING) {
                return null;
            }
        } catch (Exception e) {
            log.debug("Call invite skipped: relationship unavailable conv={}: {}",
                    conversationId, e.toString());
            return null;
        }
        if (lastCallWithinGap(conversationId)) {
            return null;
        }
        String slotKey = CALL_INVITE_KEY_PREFIX + userId + ":" + character.getId();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(slotKey, "1", java.time.Duration.ofDays(7));
        if (!Boolean.TRUE.equals(acquired)) {
            return null;
        }
        String text = generateVoiceCue(userId, conversationId, "invite");
        if (text == null || text.isBlank()) {
            // 没生成出来就不打扰；把额度还回去，别让一次失败占掉一整周
            redisTemplate.delete(slotKey);
            return null;
        }
        try {
            Message invite = new Message();
            invite.setSeq(nextSeq(conversationId));
            invite.setConversationId(conversationId);
            invite.setRole("ASSISTANT");
            invite.setCharacterId(character.getId());
            invite.setContent(text);
            invite.setAudioUrl(VOICE_CALL_INVITE_MARKER);
            invite.setCreatedAt(java.time.LocalDateTime.now());
            messageMapper.insert(invite);
            log.info("Voice call invite sent: conv={}, character={}, len={}",
                    conversationId, character.getId(), text.length());
            return MessageResponse.builder()
                    .id(invite.getId())
                    .seq(invite.getSeq())
                    .conversationId(conversationId)
                    .role(invite.getRole())
                    .characterId(character.getId())
                    .content(text)
                    .audioUrl(VOICE_CALL_INVITE_MARKER)
                    .createdAt(invite.getCreatedAt())
                    .build();
        } catch (Exception e) {
            redisTemplate.delete(slotKey);
            log.warn("Voice call invite failed conv={}: {}", conversationId, e.toString());
            return null;
        }
    }

    /** 距上一次通话不足 minGap 就不邀请：刚聊过的人不需要被叫。 */
    private boolean lastCallWithinGap(Long conversationId) {
        List<Message> summaries = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getAudioUrl, VOICE_CALL_SUMMARY_MARKER)
                .orderByDesc(Message::getSeq)
                .last("LIMIT 1"));
        if (summaries == null || summaries.isEmpty()) {
            return false;
        }
        java.time.LocalDateTime at = summaries.get(0).getCreatedAt();
        return at != null && at.isAfter(java.time.LocalDateTime.now()
                .minusHours(Math.max(0, callInviteMinGapHours)));
    }

    /**
     * Hang up: insert a WeChat-style duration bubble for UI, with a model-facing summary in context_content.
     */
    @Transactional
    public MessageResponse endCall(Long userId, Long conversationId, VoiceCallEndRequest request) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持单聊语音通话");
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);
        if (resolveCallTarget(userId, character) == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色暂不支持语音通话");
        }

        int durationSeconds = request == null ? 0 : Math.max(0, request.getDurationSeconds());
        List<VoiceCallEndRequest.VoiceCallTurnSnippet> turns =
                request == null || request.getTurns() == null ? List.of() : request.getTurns();

        String display = "我们进行了" + formatDurationZh(durationSeconds) + "的语音通话";
        // 先用本地兜底摘要落库，AI 精炼摘要异步回填 context_content
        String summary = fallbackSummary(turns);
        String contextContent = "（用户和角色进行了语音通话（" + summary + "））";

        long seq = nextSeq(conversationId);
        Message assistantMsg = new Message();
        assistantMsg.setSeq(seq);
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setCharacterId(character.getId());
        assistantMsg.setContent(display);
        assistantMsg.setContextContent(contextContent);
        assistantMsg.setAudioUrl("system/voice-call-summary");
        messageMapper.insert(assistantMsg);

        String transcript = buildTranscript(turns);
        if (!transcript.isBlank()) {
            aiBackgroundPublisher.publish(AiBackgroundTask.voiceCallSummary(
                    userId, conversationId, assistantMsg.getId(), transcript));
        }
        if (voicemailEnabled) {
            // 留言是异步的：挂断接口不该等一次模型生成 + 一次 TTS + 一次上传
            aiBackgroundPublisher.publish(AiBackgroundTask.voiceCallVoicemail(
                    userId, conversationId, character.getId()));
        }

        memoryWriter.enqueueSummary(conversationId, character.getId(), userId, null, null);
        log.info("Voice call ended: convId={}, durationSec={}, summaryLen={}",
                conversationId, durationSeconds, summary.length());

        return MessageResponse.builder()
                .id(assistantMsg.getId())
                .seq(assistantMsg.getSeq())
                .conversationId(conversationId)
                .role(assistantMsg.getRole())
                .characterId(character.getId())
                .content(display)
                .audioUrl("system/voice-call-summary")
                .createdAt(assistantMsg.getCreatedAt())
                .build();
    }

    /** MQ 消费：用模型摘要回填通话气泡的 context_content。 */
    public void processVoiceCallSummaryJob(AiBackgroundTask task) {
        if (task == null || task.messageId() == null || task.userId() == null) {
            return;
        }
        Message msg = messageMapper.selectById(task.messageId());
        if (msg == null || !"ASSISTANT".equalsIgnoreCase(msg.getRole())) {
            return;
        }
        if (msg.getAudioUrl() == null || !msg.getAudioUrl().contains("voice-call-summary")) {
            return;
        }
        String summary = summarizeCallFromTranscript(task.userId(), task.transcript());
        if (summary == null || summary.isBlank()) {
            return;
        }
        Message patch = new Message();
        patch.setId(msg.getId());
        patch.setContextContent("（用户和角色进行了语音通话（" + summary + "））");
        messageMapper.updateById(patch);
        log.info("Voice call summary refined: messageId={}, summaryLen={}", msg.getId(), summary.length());
    }

    /**
     * MQ 消费：挂断语音留言 —— 生成一句告别 → 合成 → 上传 → 落一条可播放消息。
     *
     * <p>任何一步失败都只少一条留言：挂断接口已经把结果返回给用户了，
     * 这条异步链路上不能再抛错，也不能重试打扰。
     */
    public void processVoiceCallVoicemailJob(AiBackgroundTask task) {
        if (!voicemailEnabled || task == null || task.userId() == null
                || task.conversationId() == null || task.characterId() == null) {
            return;
        }
        try {
            Character character = characterMapper.selectById(task.characterId());
            if (character == null) {
                return;
            }
            String text = generateVoiceCue(task.userId(), task.conversationId(), "voicemail");
            if (text == null || text.isBlank()) {
                return;
            }
            VoiceCallTarget target = resolveCallTarget(task.userId(), character);
            TtsPort.SynthesizedAudio audio = synthesizeForTarget(target, text);
            if (audio == null || audio.bytes() == null || audio.bytes().length == 0) {
                return;
            }
            // 本地音色没有服务端合成，target 为 null 时这里已经返回；petId 只用于对象路径
            String petId = target.petId() != null ? target.petId() : "custom";
            String objectKey = fileStorageService.uploadChatVoiceBytes(
                    petId, audio.bytes(), audio.mimeType());
            Message msg = new Message();
            msg.setSeq(nextSeq(task.conversationId()));
            msg.setConversationId(task.conversationId());
            msg.setRole("ASSISTANT");
            msg.setCharacterId(character.getId());
            msg.setContent(text);
            msg.setAudioUrl(fileStorageService.resolvePublicUrl(objectKey));
            msg.setCreatedAt(java.time.LocalDateTime.now());
            messageMapper.insert(msg);
            log.info("Voice call voicemail stored conv={} bytes={}",
                    task.conversationId(), audio.bytes().length);
        } catch (Exception e) {
            log.warn("Voice call voicemail failed conv={}: {}", task.conversationId(), e.toString());
        }
    }

    public String resolveVoicePetId(Character character) {
        if (character == null || character.getSourceTemplateId() == null) {
            return null;
        }
        CharacterSquareTemplate template = squareTemplateMapper.selectById(character.getSourceTemplateId());
        if (template == null || template.getSlug() == null) {
            return null;
        }
        String slug = template.getSlug().trim().toLowerCase(Locale.ROOT);
        return petVoiceRegistry.hasVoice(slug) ? slug : null;
    }

    private String generateShortVoiceReply(Long userId,
                                           Long conversationId,
                                           Character character,
                                           List<Message> history,
                                           String userText) {
        VaultEntryResponse userVault = apiKeyVaultService.resolvePreferredUserVault(userId);
        if (userVault == null) {
            log.debug("Voice call reply skipped AI: no user text model, userId={}", userId);
            return "我在听，你再说一遍。";
        }
        AiChatRequest aiRequest = buildVoiceAiRequest(
                userId, conversationId, character, history, userText, userVault);
        AgentChatResult chatResult = agentBlockingPort.complete(
                userId, aiRequest.getMessages(), aiRequest.getProvider(), aiRequest.getModel(),
                aiRequest.getTemperature(), aiRequest.getMaxTokens());
        if (chatResult == null) {
            return "我在听，你再说一遍。";
        }
        String raw = chatResult.content() == null ? "" : chatResult.content().trim();
        // 朗读标记必须在这里剥掉：这条路是**整句合成**，标记留进去会被念出来，
        // 也会跟着落库出现在聊天记录里。（这里的停顿由标点承担；按标记切片是双工路径的事）
        String spoken = InnerThoughtFilter.strip(SentenceSplitter.stripPauseMarkers(raw));
        if (spoken.isBlank() && !raw.isBlank()) {
            log.warn("Voice call reply became empty after stripping inner thoughts, rawLen={}", raw.length());
        }
        if (spoken.isBlank()) {
            log.warn("Voice call empty spoken reply, falling back; rawBlank={}", raw.isBlank());
            spoken = "我在听，你再说一遍。";
        }
        return clampReply(spoken);
    }

    private AiChatRequest buildVoiceAiRequest(Long userId,
                                              Long conversationId,
                                              Character character,
                                              List<Message> history,
                                              String userText,
                                              VaultEntryResponse userVault) {
        String systemPrompt = chatTurnFacade.assembleSystemPrompt(
                ChatTurnScene.VOICE_CALL,
                userId,
                conversationId,
                character,
                userText,
                userText,
                "",
                null,
                maxReplyChars);

        AiChatRequest aiRequest = new AiChatRequest();
        aiRequest.setProvider(userVault.getProvider());
        aiRequest.setModel(userVault.getModelDefault());
        aiRequest.setTemperature(0.7);
        aiRequest.setMaxTokens(Math.max(256, maxTokens));
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(messageDto("system", systemPrompt));
        for (Message msg : history) {
            if (msg.getId() == null) {
                continue;
            }
            String role = msg.getRole() == null ? "user" : msg.getRole().toLowerCase(Locale.ROOT);
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            String content = InnerThoughtFilter.strip(MessageModelContent.forModel(msg));
            if (content.isBlank()) {
                continue;
            }
            allMessages.add(messageDto(role, content));
        }
        allMessages.add(messageDto("user", userText));
        aiRequest.setMessages(allMessages);
        return aiRequest;
    }

    private String summarizeCallFromTranscript(Long userId, String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return null;
        }
        VaultEntryResponse userVault = apiKeyVaultService.resolvePreferredUserVault(userId);
        if (userVault == null) {
            log.debug("Voice call summary skipped AI: no user text model, userId={}", userId);
            return null;
        }
        try {
            String content = agentTextPort.complete(
                    userId,
                    "你是通话摘要助手。根据语音通话片段，用一句中文概括双方大概聊了什么。"
                            + "要求：不超过36字；不要引号；不要换行；不要出现「摘要」「总结」字样；只输出概括正文。",
                    transcript, userVault.getProvider(), userVault.getModelDefault(), 0.3);
            if (content == null) {
                return null;
            }
            String raw = content.trim()
                    .replace('\n', ' ')
                    .replace("\"", "")
                    .replace("「", "")
                    .replace("」", "");
            if (raw.length() > 40) {
                raw = raw.substring(0, 40).trim();
            }
            return raw.isBlank() ? null : raw;
        } catch (Exception e) {
            log.warn("Voice call summary LLM failed: {}", e.toString());
            return null;
        }
    }

    private static String buildTranscript(List<VoiceCallEndRequest.VoiceCallTurnSnippet> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (VoiceCallEndRequest.VoiceCallTurnSnippet turn : turns) {
            if (turn == null) {
                continue;
            }
            String user = turn.getUserText() == null ? "" : turn.getUserText().trim();
            String reply = turn.getReplyText() == null ? "" : turn.getReplyText().trim();
            if (user.isBlank() && reply.isBlank()) {
                continue;
            }
            n += 1;
            if (n > 24) {
                break;
            }
            if (!user.isBlank()) {
                sb.append("用户：").append(UserInputSanitizer.sanitizeChatMessage(user).storedText()).append('\n');
            }
            if (!reply.isBlank()) {
                sb.append("角色：").append(reply).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String fallbackSummary(List<VoiceCallEndRequest.VoiceCallTurnSnippet> turns) {
        if (turns == null || turns.isEmpty()) {
            return "短暂寒暄";
        }
        for (VoiceCallEndRequest.VoiceCallTurnSnippet turn : turns) {
            if (turn == null) {
                continue;
            }
            String user = turn.getUserText() == null ? "" : turn.getUserText().trim();
            if (!user.isBlank()) {
                String cleaned = UserInputSanitizer.sanitizeChatMessage(user).storedText();
                if (cleaned.length() > 28) {
                    return cleaned.substring(0, 28) + "…";
                }
                return cleaned.isBlank() ? "日常闲聊" : cleaned;
            }
        }
        return "日常闲聊";
    }

    static String formatDurationZh(int totalSeconds) {
        int seconds = Math.max(0, totalSeconds);
        if (seconds < 60) {
            return seconds + "秒";
        }
        int minutes = seconds / 60;
        int rem = seconds % 60;
        if (rem == 0) {
            return minutes + "分钟";
        }
        return minutes + "分" + rem + "秒";
    }

    private static MessageDto messageDto(String role, String content) {
        MessageDto dto = new MessageDto();
        dto.setRole(role);
        dto.setContent(content == null ? "" : content);
        return dto;
    }

    private Conversation findOwned(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !userId.equals(conversation.getUserId())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private void ensureCharacterNotBlocked(Character character) {
        if (isBlocked(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色已被拉黑，无法发送消息");
        }
    }

    private boolean isBlocked(Character character) {
        java.util.Map<String, Object> settings = character.getSettings();
        Object raw = settings == null ? null : settings.get("blocked");
        return raw instanceof Boolean b ? b : raw instanceof String s && Boolean.parseBoolean(s);
    }

    private long nextSeq(Long conversationId) {
        Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, 1);
        return lastSeq != null ? lastSeq : 1L;
    }

    /**
     * 仅「当前通话」内的 turn：最近一次 hangup 摘要之后的消息；无摘要则从会话头开始。
     * {@code messageLimit} 为本通最多带入 LLM 的句数（不是轮数）。
     * 上一通 turn 挂断后只以摘要形式进文字聊，新通话与之完全无关。
     */
    private List<Message> recentVoiceCallTurnsInCurrentCall(Long conversationId, int messageLimit) {
        int msgLimit = clampHistoryMessageLimit(messageLimit);
        Long afterSeq = lastVoiceCallSummarySeq(conversationId);
        LambdaQueryWrapper<Message> q = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getAudioUrl, VOICE_CALL_TURN_MARKER)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + msgLimit);
        if (afterSeq != null) {
            q.gt(Message::getSeq, afterSeq);
        }
        List<Message> messages = messageMapper.selectList(q);
        Collections.reverse(messages);
        return messages;
    }

    private static int clampHistoryMessageLimit(int configured) {
        return Math.max(1, Math.min(configured, 16));
    }

    private Long lastVoiceCallSummarySeq(Long conversationId) {
        List<Message> summaries = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getAudioUrl, VOICE_CALL_SUMMARY_MARKER)
                .orderByDesc(Message::getSeq)
                .last("LIMIT 1"));
        if (summaries == null || summaries.isEmpty()) {
            return null;
        }
        return summaries.get(0).getSeq();
    }

    /** Duplex persists the user turn before building AI request — drop duplicate trailing user line. */
    private static List<Message> trimCurrentUserTurn(List<Message> history, String userText) {
        if (history == null || history.isEmpty() || userText == null) {
            return history == null ? List.of() : history;
        }
        Message last = history.get(history.size() - 1);
        if ("USER".equalsIgnoreCase(last.getRole()) && userText.equals(last.getContent())) {
            return history.subList(0, history.size() - 1);
        }
        return history;
    }
}
