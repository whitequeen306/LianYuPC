package com.lianyu.service.conversation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.AssistantReplyService;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.SendMessageRequest;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class ConversationServiceStreamErrorTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private GroupMemberMapper groupMemberMapper;
    @Mock private CharacterMapper characterMapper;
    @Mock private com.lianyu.dao.mapper.CharacterSquareTemplateMapper squareTemplateMapper;
    @Mock private UserMapper userMapper;
    @Mock private AiChatService aiChatService;
    @Mock private com.lianyu.service.ai.PetMeetVoiceCatalog petMeetVoiceCatalog;
    @Mock private com.lianyu.service.ai.PetVoiceRegistry petVoiceRegistry;
    @Mock private com.lianyu.service.ai.DashScopeTtsService dashScopeTtsService;
    @Mock private ChatTurnFacade chatTurnFacade;
    @Mock private CharacterPromptBuilder promptBuilder;
    @Mock private MemoryRetriever memoryRetriever;
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
    @Mock private ProactiveRealWorldContextService proactiveRealWorldContext;
    @Mock private RelationshipStateService relationshipStateService;
    @Mock private ProactiveUnrepliedThrottle proactiveUnrepliedThrottle;
    @Mock private com.lianyu.service.tools.TimeTool timeTool;
    @Mock private SessionSummaryService sessionSummaryService;
    @Mock private CharacterRecentActivityService characterRecentActivityService;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(
                conversationMapper,
                messageMapper,
                groupMemberMapper,
                characterMapper,
                squareTemplateMapper,
                userMapper,
                aiChatService,
                petMeetVoiceCatalog,
                petVoiceRegistry,
                dashScopeTtsService,
                chatTurnFacade,
                promptBuilder,
                memoryRetriever,
                memoryWriter,
                redisTemplate,
                fileStorageService,
                chatBehaviorResolver,
                assistantReplyService,
                objectMapper,
                notificationService,
                outputLanguageService,
                characterStateService,
                proactiveRealWorldContext,
                relationshipStateService,
                proactiveUnrepliedThrottle,
                timeTool,
                sessionSummaryService,
                characterRecentActivityService);
        ReflectionTestUtils.setField(service, "contextWindow", 20);
    }

    @Test
    void streamCallbackWithError_doesNotRecordAssistantTurn() {
        long userId = 1L;
        long convId = 9L;
        long charId = 5L;

        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setUserId(userId);
        conv.setCharacterId(charId);
        conv.setMode("SINGLE");

        Character character = new Character();
        character.setId(charId);
        character.setOwnerUserId(userId);
        character.setName("Test");

        when(conversationMapper.selectById(convId)).thenReturn(conv);
        when(characterMapper.selectById(charId)).thenReturn(character);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), eq(1L))).thenReturn(100L);
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(chatBehaviorResolver.resolve(any())).thenReturn(new CharacterChatBehavior(3, true, 60, 0.9, 55, 65, null));

        ArgumentCaptor<AiChatService.StreamCallback> callbackCaptor = ArgumentCaptor.forClass(AiChatService.StreamCallback.class);
        when(chatTurnFacade.invokeStream(any(), callbackCaptor.capture())).thenReturn(new SseEmitter());

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("hello");
        service.sendMessageStream(userId, convId, request);

        callbackCaptor.getValue().onComplete("partial text", new RuntimeException("provider down"));

        verify(relationshipStateService, never()).recordAssistantTurn(anyLong(), anyLong(), anyLong(), any());
        verify(memoryWriter, never()).enqueueSummary(anyLong(), anyLong(), anyLong());
        verify(messageMapper, never()).insert(argThatAssistantMessage());
    }

    @Test
    void streamCallbackWithProviderConnectionReset_doesNotRecordAssistantTurn() {
        long userId = 1L;
        long convId = 9L;
        long charId = 5L;

        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setUserId(userId);
        conv.setCharacterId(charId);
        conv.setMode("SINGLE");

        Character character = new Character();
        character.setId(charId);
        character.setOwnerUserId(userId);
        character.setName("Test");

        when(conversationMapper.selectById(convId)).thenReturn(conv);
        when(characterMapper.selectById(charId)).thenReturn(character);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString(), eq(1L))).thenReturn(100L);
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(chatBehaviorResolver.resolve(any())).thenReturn(new CharacterChatBehavior(3, true, 60, 0.9, 55, 65, null));

        ArgumentCaptor<AiChatService.StreamCallback> callbackCaptor = ArgumentCaptor.forClass(AiChatService.StreamCallback.class);
        when(chatTurnFacade.invokeStream(any(), callbackCaptor.capture())).thenReturn(new SseEmitter());

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("hello");
        service.sendMessageStream(userId, convId, request);

        callbackCaptor.getValue().onComplete(
                "partial text",
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Connection reset"));

        verify(relationshipStateService, never()).recordAssistantTurn(anyLong(), anyLong(), anyLong(), any());
        verify(memoryWriter, never()).enqueueSummary(anyLong(), anyLong(), anyLong());
        verify(messageMapper, never()).insert(argThatAssistantMessage());
    }

    @Test
    void streamCallbackWithClientDisconnectAndFullContent_recordsAssistantTurn() {
        long userId = 1L;
        long convId = 9L;
        long charId = 5L;

        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setUserId(userId);
        conv.setCharacterId(charId);
        conv.setMode("SINGLE");

        Character character = new Character();
        character.setId(charId);
        character.setOwnerUserId(userId);
        character.setName("Test");

        when(conversationMapper.selectById(convId)).thenReturn(conv);
        when(characterMapper.selectById(charId)).thenReturn(character);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(chatBehaviorResolver.resolve(any())).thenReturn(new CharacterChatBehavior(3, true, 60, 0.9, 55, 65, null));
        when(assistantReplyService.process(eq("complete reply"), eq(3)))
                .thenReturn(new AssistantReplyService.ProcessedReply("complete reply", List.of("complete reply")));
        when(valueOperations.increment(anyString(), eq(1L))).thenReturn(100L, 101L);

        ArgumentCaptor<AiChatService.StreamCallback> callbackCaptor = ArgumentCaptor.forClass(AiChatService.StreamCallback.class);
        when(chatTurnFacade.invokeStream(any(), callbackCaptor.capture())).thenReturn(new SseEmitter());

        SendMessageRequest request = new SendMessageRequest();
        request.setContent("hello");
        service.sendMessageStream(userId, convId, request);

        callbackCaptor.getValue().onComplete("complete reply", new IOException("Broken pipe"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper, atLeastOnce()).insert(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .anySatisfy(message -> {
                    assertThat(message.getRole()).isEqualTo("ASSISTANT");
                    assertThat(message.getContent()).isEqualTo("complete reply");
                });
        verify(relationshipStateService).recordAssistantTurn(eq(userId), eq(charId), eq(convId), any());
        verify(memoryWriter).enqueueSummary(eq(convId), eq(charId), eq(userId));
    }

    private Message argThatAssistantMessage() {
        return org.mockito.ArgumentMatchers.argThat((ArgumentMatcher<Message>) message ->
                message != null && "ASSISTANT".equals(message.getRole()));
    }
}
