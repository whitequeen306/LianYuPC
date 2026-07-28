package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
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
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.ai.DashScopeTtsService;
import com.lianyu.service.ai.PetVoiceRegistry;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.VoiceCallEndRequest;
import com.lianyu.service.dto.VoiceCallTurnResponse;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.graph.MessageModelContent;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.tools.ChatToolContext;
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
    /** V1: only Raiden voice call for testing. */
    private static final Set<String> VOICE_CALL_PET_IDS = Set.of("raiden");

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final CharacterSquareTemplateMapper squareTemplateMapper;
    private final AsrService asrService;
    private final PetVoiceRegistry petVoiceRegistry;
    private final DashScopeTtsService dashScopeTtsService;
    private final AiChatService aiChatService;
    private final ChatTurnFacade chatTurnFacade;
    private final MemoryWriter memoryWriter;
    private final CharacterStateService characterStateService;
    private final RelationshipStateService relationshipStateService;
    private final StringRedisTemplate redisTemplate;

    @Value("${lianyu.voice-call.max-reply-chars:48}")
    private int maxReplyChars;

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

        String userText = asrService.transcribe(audio);
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

        List<Message> history = recentMessages(conversationId, 24);
        relationshipStateService.recordUserTurn(userId, character.getId(), conversationId, userMsg, history);
        characterStateService.afterUserMessage(character.getId(), userId, userText);

        String replyText = generateShortVoiceReply(userId, conversationId, character, history, userText);
        if (replyText.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "角色暂时无法回复，请稍后再试");
        }

        DashScopeTtsService.SynthesizedAudio audioOut = dashScopeTtsService.synthesizeForPet(petId, replyText);
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

        memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
        log.info("Voice call turn: convId={}, petId={}, userLen={}, replyLen={}",
                conversationId, petId, userText.length(), replyText.length());

        return VoiceCallTurnResponse.builder()
                .userText(userText)
                .replyText(replyText)
                .audioBase64(audioOut.base64())
                .audioMimeType(audioOut.mimeType())
                .userMessageId(userMsg.getId())
                .replyMessageId(assistantMsg.getId())
                .build();
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
        String summary = summarizeCall(userId, turns);
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
        String voiceSuffix = "\n\n=== 语音通话 ===\n"
                + "你正在与用户进行实时语音通话。请用口语化中文回复，"
                + "总字数不超过 " + maxReplyChars + " 字，1～2 句即可，不要列表、不要 markdown。";
        String systemPrompt = chatTurnFacade.assembleSystemPrompt(
                ChatTurnScene.SINGLE,
                userId,
                conversationId,
                character,
                userText,
                userText,
                voiceSuffix,
                null);

        AiChatRequest aiRequest = new AiChatRequest();
        ChatToolContext.bindTo(aiRequest, character);
        aiRequest.setProvider(AiConstants.PLATFORM_PROVIDER);
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
            allMessages.add(messageDto(role, MessageModelContent.forModel(msg)));
        }
        allMessages.add(messageDto("user", userText));
        aiRequest.setMessages(allMessages);

        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        String raw = chatResult.getContent() == null ? "" : chatResult.getContent().trim();
        if (raw.length() > maxReplyChars) {
            raw = raw.substring(0, maxReplyChars).trim();
        }
        return raw;
    }

    private String summarizeCall(Long userId, List<VoiceCallEndRequest.VoiceCallTurnSnippet> turns) {
        String transcript = buildTranscript(turns);
        if (transcript.isBlank()) {
            return "短暂寒暄";
        }
        try {
            AiChatRequest aiRequest = new AiChatRequest();
            aiRequest.setProvider(AiConstants.PLATFORM_PROVIDER);
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
            if (!raw.isBlank()) {
                return raw;
            }
        } catch (Exception e) {
            log.warn("Voice call summary LLM failed, using fallback: {}", e.toString());
        }
        return fallbackSummary(turns);
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

    private List<Message> recentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + limit));
        Collections.reverse(messages);
        return messages;
    }
}
