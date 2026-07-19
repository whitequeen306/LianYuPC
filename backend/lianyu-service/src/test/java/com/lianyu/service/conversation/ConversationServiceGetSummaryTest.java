package com.lianyu.service.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.ConversationResponse;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConversationServiceGetSummaryTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private GroupMemberMapper groupMemberMapper;
    @Mock private CharacterMapper characterMapper;
    @Mock private UserMapper userMapper;
    @Mock private AiChatService aiChatService;
    @Mock private CharacterPromptBuilder promptBuilder;
    @Mock private MemoryRetriever memoryRetriever;
    @Mock private MemoryWriter memoryWriter;
    @Mock private StringRedisTemplate redisTemplate;
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
                mock(com.lianyu.dao.mapper.CharacterSquareTemplateMapper.class),
                userMapper,
                aiChatService,
                mock(com.lianyu.service.ai.PetMeetVoiceCatalog.class),
                mock(com.lianyu.service.ai.PetVoiceRegistry.class),
                null,
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
    void get_returnsConversationSummarySnippets() {
        long userId = 1L;
        long convId = 9L;
        long charId = 5L;

        Conversation conv = new Conversation();
        conv.setId(convId);
        conv.setUserId(userId);
        conv.setCharacterId(charId);
        conv.setMode("SINGLE");
        conv.setTitle("Test");

        Character character = new Character();
        character.setId(charId);
        character.setName("Test");
        character.setAvatarUrl("avatar/test.png");

        Message lastMessage = new Message();
        lastMessage.setConversationId(convId);
        lastMessage.setContent("用户最新一句");

        Message lastAssistant = new Message();
        lastAssistant.setConversationId(convId);
        lastAssistant.setContent("角色最新一句");

        when(conversationMapper.selectById(convId)).thenReturn(conv);
        when(characterMapper.selectById(charId)).thenReturn(character);
        when(fileStorageService.resolvePublicUrl(anyString())).thenReturn("https://cdn/avatar/test.png");
        when(fileStorageService.resolveSquareAvatarThumbPublicUrl(anyString())).thenReturn("https://cdn/avatar/test-thumb.png");
        when(messageMapper.selectLatestByConversationIds(List.of(convId))).thenReturn(List.of(lastMessage));
        when(messageMapper.selectLatestAssistantByConversationIds(List.of(convId))).thenReturn(List.of(lastAssistant));

        ConversationResponse response = service.get(userId, convId);

        assertThat(response.getLastMessage()).isEqualTo("用户最新一句");
        assertThat(response.getLastCharacterMessage()).isEqualTo("角色最新一句");
    }
}
