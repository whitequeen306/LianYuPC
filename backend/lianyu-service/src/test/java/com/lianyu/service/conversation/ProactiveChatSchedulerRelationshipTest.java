package com.lianyu.service.conversation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterStateMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.relationship.RelationshipPhase;
import com.lianyu.service.relationship.RelationshipSnapshot;
import com.lianyu.service.relationship.RelationshipStateService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProactiveChatSchedulerRelationshipTest {

    @Test
    void isEligible_rejectsProactiveMessageWhenRelationshipIsFreshlyInjured() throws Exception {
        ConversationMapper conversationMapper = Mockito.mock(ConversationMapper.class);
        MessageMapper messageMapper = Mockito.mock(MessageMapper.class);
        CharacterMapper characterMapper = Mockito.mock(CharacterMapper.class);
        CharacterStateMapper characterStateMapper = Mockito.mock(CharacterStateMapper.class);
        ConversationService conversationService = Mockito.mock(ConversationService.class);
        CharacterChatBehaviorResolver chatBehaviorResolver = Mockito.mock(CharacterChatBehaviorResolver.class);
        EngagementFrequencyScorer engagementScorer = Mockito.mock(EngagementFrequencyScorer.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        RelationshipStateService relationshipStateService = Mockito.mock(RelationshipStateService.class);
        ProactiveUnrepliedThrottle proactiveUnrepliedThrottle = Mockito.mock(ProactiveUnrepliedThrottle.class);

        ProactiveChatScheduler scheduler = new ProactiveChatScheduler(
                conversationMapper,
                messageMapper,
                characterMapper,
                characterStateMapper,
                conversationService,
                chatBehaviorResolver,
                engagementScorer,
                redisTemplate,
                relationshipStateService,
                proactiveUnrepliedThrottle);

        Conversation conversation = new Conversation();
        conversation.setId(11L);
        conversation.setUserId(3L);
        conversation.setCharacterId(5L);
        conversation.setMode("SINGLE");

        CharacterChatBehavior behavior = new CharacterChatBehavior(1, true, 5, 0.5d, 5, 10, "gentle");

        Message lastMessage = new Message();
        lastMessage.setRole("USER");
        lastMessage.setCreatedAt(LocalDateTime.now());

        when(relationshipStateService.getSnapshot(3L, 5L)).thenReturn(
                RelationshipSnapshot.builder()
                        .trustScore(40)
                        .intimacyScore(22)
                        .securityScore(15)
                        .anticipationScore(12)
                        .phase(RelationshipPhase.INJURED)
                        .build());

        java.lang.reflect.Method isEligibleMethod = ProactiveChatScheduler.class.getDeclaredMethod(
                "isEligible",
                Conversation.class,
                CharacterChatBehavior.class,
                int.class,
                Message.class,
                Message.class,
                RelationshipPhase.class);
        isEligibleMethod.setAccessible(true);
        boolean result = (boolean) isEligibleMethod.invoke(
                scheduler,
                conversation,
                behavior,
                behavior.minIdleMinutes(),
                lastMessage,
                lastMessage,
                RelationshipPhase.INJURED);

        assertFalse(result);
    }
}