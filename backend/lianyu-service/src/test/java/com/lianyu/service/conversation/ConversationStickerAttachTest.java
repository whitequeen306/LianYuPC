package com.lianyu.service.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.AssistantReplyService;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 表情包落库口径：文字可以有多段，图只挂在**最后一段**上（顺序 = 先说话、再甩图）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationStickerAttachTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private GroupMemberMapper groupMemberMapper;
    @Mock private CharacterMapper characterMapper;
    @Mock private ChatTurnFacade chatTurnFacade;
    @Mock private MemoryWriter memoryWriter;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private FileStorageService fileStorageService;
    @Mock private CharacterChatBehaviorResolver chatBehaviorResolver;
    @Mock private AssistantReplyService assistantReplyService;
    @Mock private ObjectMapper objectMapper;
    @Mock private NotificationService notificationService;
    @Mock private OutputLanguageService outputLanguageService;
    @Mock private CharacterStateService characterStateService;
    @Mock private RelationshipStateService relationshipStateService;
    @Mock private ProactiveUnrepliedThrottle proactiveUnrepliedThrottle;
    @Mock private TimeTool timeTool;
    @Mock private SessionSummaryService sessionSummaryService;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(
                conversationMapper,
                messageMapper,
                groupMemberMapper,
                characterMapper,
                mock(com.lianyu.service.ai.ApiKeyVaultService.class),
                chatTurnFacade,
                memoryWriter,
                redisTemplate,
                fileStorageService,
                chatBehaviorResolver,
                assistantReplyService,
                objectMapper,
                notificationService,
                outputLanguageService,
                characterStateService,
                relationshipStateService,
                proactiveUnrepliedThrottle,
                timeTool,
                sessionSummaryService);
    }

    @Test
    void stickerIsAttachedToLastPieceOnly() {
        Character character = new Character();
        character.setId(1002L);
        when(chatBehaviorResolver.resolve(any())).thenReturn(
                new CharacterChatBehavior(3, true, 60, 0.5, 55, 65, "gentle"));
        when(assistantReplyService.process(anyString(), anyInt()))
                .thenReturn(new AssistantReplyService.ProcessedReply("好呀\n给你一个", List.of("好呀", "给你一个")));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(2L);
        List<Message> inserted = new ArrayList<>();
        when(messageMapper.insert(any(Message.class))).thenAnswer(inv -> {
            inserted.add(inv.getArgument(0));
            return 1;
        });

        List<MessageResponse> replies = service.saveAssistantRepliesLimited(
                9L, character, "好呀\n给你一个", 12, 3, "/api/public/files/stickers/happy.png");

        assertEquals(2, replies.size());
        assertEquals(2, inserted.size());
        assertNull(inserted.get(0).getImageUrl(), "第一段只是文字");
        assertEquals("/api/public/files/stickers/happy.png", inserted.get(1).getImageUrl());
    }

    @Test
    void noStickerKeepsMessagesClean() {        Character character = new Character();
        character.setId(1002L);
        when(chatBehaviorResolver.resolve(any())).thenReturn(
                new CharacterChatBehavior(3, true, 60, 0.5, 55, 65, "gentle"));
        when(assistantReplyService.process(anyString(), anyInt()))
                .thenReturn(new AssistantReplyService.ProcessedReply("嗯", List.of("嗯")));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), anyLong())).thenReturn(1L);
        List<Message> inserted = new ArrayList<>();
        when(messageMapper.insert(any(Message.class))).thenAnswer(inv -> {
            inserted.add(inv.getArgument(0));
            return 1;
        });

        service.saveAssistantRepliesLimited(9L, character, "嗯", 3, 3, null);

        assertEquals(1, inserted.size());
        assertNull(inserted.get(0).getImageUrl());
    }

    @Test
    void stickerBytesAreAttachedForVisionReview() {
        when(fileStorageService.readObjectBytes("chat-images/a.png")).thenReturn(new byte[]{1, 2, 3});
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("url", "/api/public/files/chat-images/a.png");
        candidate.put("emotion", "happy");

        List<Map<String, String>> enriched = service.withStickerImages(List.of(candidate));

        assertEquals(1, enriched.size());
        assertEquals("AQID", enriched.get(0).get("imageBase64"));
        assertEquals("happy", enriched.get(0).get("emotion"), "原有字段不能被覆盖掉");
    }

    @Test
    void unreadableStickerStillSurvivesWithoutBytes() {
        when(fileStorageService.readObjectBytes("chat-images/b.png"))
                .thenThrow(new IllegalStateException("minio down"));
        Map<String, String> candidate = new LinkedHashMap<>();
        candidate.put("url", "/api/public/files/chat-images/b.png");

        List<Map<String, String>> enriched = service.withStickerImages(List.of(candidate));

        assertEquals(1, enriched.size());
        assertNull(enriched.get(0).get("imageBase64"), "读不到字节就只给 URL，词表兜底照旧能用");
        assertEquals("/api/public/files/chat-images/b.png", enriched.get(0).get("url"));
    }
}
