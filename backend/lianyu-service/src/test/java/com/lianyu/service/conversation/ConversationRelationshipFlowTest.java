package com.lianyu.service.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.AssistantReplyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.graph.ChatTurnCommand;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.graph.ChatTurnResult;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.SendMessageRequest;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ConversationRelationshipFlowTest {

    @Test
    void sendMessage_buildsPromptWithRelationshipContextAndRecordsUserEvents() throws Exception {
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        MessageMapper messageMapper = mock(MessageMapper.class);
        GroupMemberMapper groupMemberMapper = mock(GroupMemberMapper.class);
        CharacterMapper characterMapper = mock(CharacterMapper.class);
        AiChatService aiChatService = mock(AiChatService.class);
        ChatTurnFacade chatTurnFacade = mock(ChatTurnFacade.class);
        CharacterPromptBuilder promptBuilder = mock(CharacterPromptBuilder.class);
        MemoryRetriever memoryRetriever = mock(MemoryRetriever.class);
        MemoryWriter memoryWriter = mock(MemoryWriter.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        CharacterChatBehaviorResolver chatBehaviorResolver = mock(CharacterChatBehaviorResolver.class);
        AssistantReplyService assistantReplyService = mock(AssistantReplyService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationService notificationService = mock(NotificationService.class);
        OutputLanguageService outputLanguageService = mock(OutputLanguageService.class);
        CharacterStateService characterStateService = mock(CharacterStateService.class);
        ProactiveRealWorldContextService proactiveRealWorldContext = mock(ProactiveRealWorldContextService.class);
        RelationshipStateService relationshipStateService = mock(RelationshipStateService.class);
        ProactiveUnrepliedThrottle proactiveUnrepliedThrottle = mock(ProactiveUnrepliedThrottle.class);
        com.lianyu.service.tools.TimeTool timeTool = mock(com.lianyu.service.tools.TimeTool.class);
        SessionSummaryService sessionSummaryService = mock(SessionSummaryService.class);

        com.lianyu.dao.mapper.UserMapper userMapper = mock(com.lianyu.dao.mapper.UserMapper.class);

        ConversationService conversationService = new ConversationService(
                conversationMapper,
                messageMapper,
                groupMemberMapper,
                characterMapper,
                mock(com.lianyu.dao.mapper.CharacterSquareTemplateMapper.class),
                userMapper,
                aiChatService,
                mock(com.lianyu.service.ai.PetMeetVoiceCatalog.class),
                mock(com.lianyu.service.ai.PetVoiceRegistry.class),
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
                sessionSummaryService);

        Field contextWindow = ConversationService.class.getDeclaredField("contextWindow");
        contextWindow.setAccessible(true);
        contextWindow.set(conversationService, 20);

        Conversation conversation = new Conversation();
        conversation.setId(11L);
        conversation.setUserId(3L);
        conversation.setCharacterId(5L);
        conversation.setMode("SINGLE");

        Character character = new Character();
        character.setId(5L);
        character.setOwnerUserId(3L);
        character.setName("测试角色");
        CharacterChatBehavior behavior = new CharacterChatBehavior(1, true, 5, 0.5d, 5, 10, "gentle");

        when(conversationMapper.selectById(11L)).thenReturn(conversation);
        when(characterMapper.selectById(5L)).thenReturn(character);
        when(chatBehaviorResolver.resolve(character)).thenReturn(behavior);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("msg_seq:11")).thenReturn(1L, 2L);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(chatTurnFacade.invokeBlocking(any(ChatTurnCommand.class)))
                .thenReturn(ChatTurnResult.builder()
                        .systemPrompt("关系阶段: familiar\n[system]")
                        .content("没关系，我还在。 ")
                        .totalTokens(12)
                        .build());
        when(assistantReplyService.process("没关系，我还在。 ", 1))
                .thenReturn(new AssistantReplyService.ProcessedReply(
                        "没关系，我还在。", List.of("没关系，我还在。")));

        doAnswer(invocation -> {
            Message msg = invocation.getArgument(0);
            if ("USER".equalsIgnoreCase(msg.getRole())) {
                msg.setId(91L);
                msg.setCreatedAt(LocalDateTime.now());
            } else {
                msg.setId(92L);
                msg.setCreatedAt(LocalDateTime.now());
            }
            return 1;
        }).when(messageMapper).insert(any(Message.class));

        SendMessageRequest request = new SendMessageRequest();
        request.setProvider("openai");
        request.setContent("对不起，我刚才没回你");

        MessageResponse response = conversationService.sendMessage(3L, 11L, request);

        assertEquals("没关系，我还在。", response.getContent());
        verify(relationshipStateService).recordUserTurn(eq(3L), eq(5L), eq(11L), any(Message.class), anyList());
        verify(relationshipStateService).recordAssistantTurn(eq(3L), eq(5L), eq(11L), anyList());
        verify(chatTurnFacade).invokeBlocking(argThat(cmd ->
                cmd.getCharacter() != null && cmd.getCharacter().getId().equals(5L)));
    }
}
