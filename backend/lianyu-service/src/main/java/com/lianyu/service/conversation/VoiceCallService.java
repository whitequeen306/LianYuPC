package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.ai.DashScopeTtsService;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.ai.PetVoiceRegistry;
import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
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
    /**
     * Voice-call allowlist: must have realtimeVoices mapping (HTTP fallback uses voices).
     * Keep in sync with frontend {@code voiceCallPets.js}.
     */
    private static final Set<String> VOICE_CALL_PET_IDS = Set.of(
            "raiden",
            "elysia",
            "yae_miko",
            "kokomi",
            "shenhe",
            "nahida",
            "hu_tao",
            "furina",
            "noelle"
    );

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final CharacterSquareTemplateMapper squareTemplateMapper;
    private final AsrService asrService;
    private final PetVoiceRegistry petVoiceRegistry;
    private final DashScopeTtsService dashScopeTtsService;
    private final AiChatService aiChatService;
    private final ApiKeyVaultService apiKeyVaultService;
    private final ChatTurnFacade chatTurnFacade;
    private final MemoryWriter memoryWriter;
    private final StringRedisTemplate redisTemplate;
    private final AiBackgroundPublisher aiBackgroundPublisher;

    @Value("${lianyu.voice-call.max-reply-chars:48}")
    private int maxReplyChars;

    /**
     * 本通通话内最多带入 LLM 的历史句数（user+assistant 各算一句）。
     * 仅当前通话有效；挂断摘要写入后，旧 turn 不再参与任何上下文。
     */
    @Value("${lianyu.voice-call.history-limit:8}")
    private int historyLimit;

    @Value("${lianyu.voice-call.max-tokens:512}")
    private int maxTokens;

    @Transactional
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

        String petId = resolveVoicePetId(character);
        if (petId == null || !VOICE_CALL_PET_IDS.contains(petId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色暂不支持语音通话");
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

        long userSeq = nextSeq(conversationId);
        Message userMsg = new Message();
        userMsg.setSeq(userSeq);
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setCharacterId(character.getId());
        userMsg.setContent(userText);
        userMsg.setAudioUrl("system/voice-call-turn");
        messageMapper.insert(userMsg);

        // 通话内容不进关系/情绪/记忆；唯一持久作用是挂断时摘要进文字聊
        int msgLimit = clampHistoryMessageLimit(historyLimit);
        List<Message> history = recentVoiceCallTurnsInCurrentCall(conversationId, msgLimit);
        history = trimCurrentUserTurn(history, userText);

        String replyText = generateShortVoiceReply(userId, conversationId, character, history, userText);
        long tLlm = System.nanoTime();
        if (replyText.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "角色暂时无法回复，请稍后再试");
        }

        DashScopeTtsService.SynthesizedAudio audioOut = dashScopeTtsService.synthesizeForPet(petId, replyText);
        long tTts = System.nanoTime();
        if (audioOut == null || audioOut.base64() == null || audioOut.base64().isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音合成失败，请稍后再试");
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

        log.info("Voice call turn: convId={}, petId={}, userLen={}, replyLen={}, asrMs={}, llmMs={}, ttsMs={}",
                conversationId, petId, userText.length(), replyText.length(),
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

    /** Duplex: assert ownership + supported pet, return petId. */
    public String resolveAndAssertCallPet(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持单聊语音通话");
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);
        String petId = resolveVoicePetId(character);
        if (petId == null || !VOICE_CALL_PET_IDS.contains(petId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前角色暂不支持语音通话");
        }
        return petId;
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
        return buildVoiceAiRequest(userId, conversationId, character, history, userText, userVault);
    }

    public java.util.concurrent.CompletableFuture<String> streamVoiceReply(
            Long userId,
            AiChatRequest aiRequest,
            java.util.function.Consumer<String> onDelta) {
        return aiChatService.streamTokens(userId, aiRequest, onDelta);
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
        String petId = resolveVoicePetId(character);
        if (petId == null || !VOICE_CALL_PET_IDS.contains(petId)) {
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

        memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
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
        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        String raw = chatResult.getContent() == null ? "" : chatResult.getContent().trim();
        String spoken = InnerThoughtFilter.strip(raw);
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
        String voiceSuffix = "\n\n=== 语音通话（强制） ===\n"
                + "你正在与用户进行实时语音通话，回复会被直接朗读。\n"
                + "硬性要求：\n"
                + "1. 只用口语化中文，1～2 句，总字数不超过 " + maxReplyChars + " 字；\n"
                + "2. 禁止任何括号（）() 及其中内容：不准写心理活动、内心独白、旁白、动作/表情描写；\n"
                + "3. 只输出可直接说出口的台词，不要列表、不要 markdown、不要引号包裹全文；\n"
                + "4. 停顿由你控制：如果你希望两句话之间有明显的停顿或换气（如反问前、情绪转折、强调前），"
                + "在该处插入 <|pause|>；每条回复最多 2 处，不要连用，不要放在整段开头或结尾；"
                + "该标记不会被朗读，也不会显示给用户。";
        String systemPrompt = chatTurnFacade.assembleSystemPrompt(
                ChatTurnScene.VOICE_CALL,
                userId,
                conversationId,
                character,
                userText,
                userText,
                voiceSuffix,
                null);

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
            AiChatRequest aiRequest = new AiChatRequest();
            aiRequest.setProvider(userVault.getProvider());
            aiRequest.setModel(userVault.getModelDefault());
            aiRequest.setBackground(true);
            List<MessageDto> messages = new ArrayList<>();
            messages.add(messageDto("system",
                    "你是通话摘要助手。根据语音通话片段，用一句中文概括双方大概聊了什么。"
                            + "要求：不超过36字；不要引号；不要换行；不要出现「摘要」「总结」字样；只输出概括正文。"));
            messages.add(messageDto("user", transcript));
            aiRequest.setMessages(messages);
            ChatResult result = aiChatService.chatBlocking(userId, aiRequest);
            String raw = result.getContent() == null ? "" : result.getContent().trim()
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
