package com.lianyu.service.character;

import com.lianyu.dao.dto.CharacterDiaryLastDateRow;
import com.lianyu.dao.dto.ConversationActivityRow;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CharacterDiaryServiceTest {

    private CharacterDiaryMapper diaryMapper;
    private CharacterMapper characterMapper;
    private ConversationMapper conversationMapper;
    private MessageMapper messageMapper;
    private AiChatService aiChatService;
    private ApiKeyVaultService apiKeyVaultService;
    private ChatTurnFacade chatTurnFacade;
    private OutputLanguageService outputLanguageService;
    private NotificationService notificationService;
    private CharacterRecentActivityService characterRecentActivityService;
    private AiBackgroundPublisher aiBackgroundPublisher;
    private CharacterDiaryService service;

    @BeforeEach
    void setUp() {
        diaryMapper = mock(CharacterDiaryMapper.class);
        characterMapper = mock(CharacterMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(MessageMapper.class);
        aiChatService = mock(AiChatService.class);
        apiKeyVaultService = mock(ApiKeyVaultService.class);
        chatTurnFacade = mock(ChatTurnFacade.class);
        outputLanguageService = mock(OutputLanguageService.class);
        notificationService = mock(NotificationService.class);
        characterRecentActivityService = mock(CharacterRecentActivityService.class);
        aiBackgroundPublisher = mock(AiBackgroundPublisher.class);
        service = new CharacterDiaryService(
                diaryMapper, characterMapper, conversationMapper, messageMapper,
                aiChatService, apiKeyVaultService, chatTurnFacade,
                mock(com.lianyu.service.ai.CharacterPromptBuilder.class),
                mock(MemoryRetriever.class), outputLanguageService,
                new ObjectMapper(), mock(RelationshipStateService.class),
                notificationService, characterRecentActivityService, aiBackgroundPublisher);

        ReflectionTestUtils.setField(service, "diaryEnabled", true);
        ReflectionTestUtils.setField(service, "scanLimit", 30);
        ReflectionTestUtils.setField(service, "minDaysBetweenDiaries", 2);
        ReflectionTestUtils.setField(service, "probabilityLight", 0.3);
        ReflectionTestUtils.setField(service, "probabilityModerate", 0.5);
        ReflectionTestUtils.setField(service, "maxPerRun", 20);
        ReflectionTestUtils.setField(service, "maxPerUserPerRun", 2);
        ReflectionTestUtils.setField(service, "impromptuMaxPerDay", 1);
        ReflectionTestUtils.setField(service, "contentMaxChars", 900);
    }

    private ConversationActivityRow activity(Long convId, Long userId, Long characterId, long userMsgs) {
        ConversationActivityRow row = new ConversationActivityRow();
        row.setConversationId(convId);
        row.setUserId(userId);
        row.setCharacterId(characterId);
        row.setUserMsgCount(userMsgs);
        row.setLastActivityAt(LocalDateTime.now());
        return row;
    }

    // ===== 档位概率（纯函数） =====

    @Test
    void probability_neverWritten_isForced() {
        assertEquals(1.0, CharacterDiaryService.resolveAttemptProbability(1, null, LocalDate.now(), 2, 0.3, 0.5));
    }

    @Test
    void probability_twoDaysSinceLastDiary_isForced() {
        LocalDate today = LocalDate.now();
        assertEquals(1.0, CharacterDiaryService.resolveAttemptProbability(1, today.minusDays(2), today, 2, 0.3, 0.5));
    }

    @Test
    void probability_writtenYesterday_usesTiers() {
        LocalDate today = LocalDate.now();
        assertEquals(0.3, CharacterDiaryService.resolveAttemptProbability(1, today.minusDays(1), today, 2, 0.3, 0.5));
        assertEquals(0.3, CharacterDiaryService.resolveAttemptProbability(2, today.minusDays(1), today, 2, 0.3, 0.5));
        assertEquals(0.5, CharacterDiaryService.resolveAttemptProbability(3, today.minusDays(1), today, 2, 0.3, 0.5));
    }

    // ===== 素材构建（纯函数） =====

    @Test
    void material_backfillFirst_thenToday_withLabelsAndTrim() {
        Message old = new Message();
        old.setRole("USER");
        old.setContent("x".repeat(130));
        Message todayUser = new Message();
        todayUser.setRole("USER");
        todayUser.setContent("今天去吃火锅吗");
        Message todayAssistant = new Message();
        todayAssistant.setRole("ASSISTANT");
        todayAssistant.setContent("好呀，我想吃番茄锅");

        String material = CharacterDiaryService.buildDiaryChatMaterial(List.of(old), List.of(todayUser, todayAssistant));

        assertTrue(material.startsWith("此前对话片段："));
        assertTrue(material.contains("对方: " + "x".repeat(120) + "…"));
        assertTrue(material.contains("今天（" + LocalDate.now() + "）的对话："));
        assertTrue(material.contains("对方: 今天去吃火锅吗"));
        assertTrue(material.contains("我: 好呀，我想吃番茄锅"));
        assertTrue(material.indexOf("此前对话片段") < material.indexOf("今天（"));
    }

    @Test
    void material_skipsBlankContent() {
        Message blank = new Message();
        blank.setRole("ASSISTANT");
        blank.setContent("  ");
        assertTrue(CharacterDiaryService.buildDiaryChatMaterial(List.of(), List.of(blank))
                .contains("今天（" + LocalDate.now() + "）的对话：\n"));
    }

    // ===== generateDiaries：两天保底 + 双上限 + 来源互斥 =====

    private void stubCandidateQueries(List<ConversationActivityRow> rows) {
        when(conversationMapper.selectSingleConversationsActiveSince(any(), Mockito.anyInt()))
                .thenReturn(rows);
        // 默认：今天没有任何日记（source 查询由 service 的 wrapper 过滤，测试中返回空表）
        when(diaryMapper.selectList(any())).thenReturn(List.of());
        when(diaryMapper.selectLastDiaryDates(anyList())).thenReturn(List.of());
    }

    @Test
    void generate_forcedCandidate_enqueuedEvenWithSingleMessage() {
        stubCandidateQueries(List.of(activity(1L, 10L, 100L, 1)));
        // 保底：从未写过日记 → 概率 1.0，必入队
        int created = service.generateDiaries();
        assertEquals(1, created);
        verify(aiBackgroundPublisher).publish(any(AiBackgroundTask.class));
    }

    @Test
    void generate_writtenYesterday_zeroProbability_notEnqueued() {
        stubCandidateQueries(List.of(activity(1L, 10L, 100L, 1)));
        ReflectionTestUtils.setField(service, "probabilityLight", 0.0);
        ReflectionTestUtils.setField(service, "probabilityModerate", 0.0);
        CharacterDiaryLastDateRow last = new CharacterDiaryLastDateRow();
        last.setCharacterId(100L);
        last.setLastDiaryAt(LocalDateTime.now().minusDays(1));
        when(diaryMapper.selectLastDiaryDates(anyList())).thenReturn(List.of(last));

        assertEquals(0, service.generateDiaries());
        verify(aiBackgroundPublisher, never()).publish(any(AiBackgroundTask.class));
    }

    @Test
    void generate_scheduledToday_skipped_impromptuToday_doesNotBlock() {
        CharacterDiary scheduled = new CharacterDiary();
        scheduled.setCharacterId(100L);
        scheduled.setSource(CharacterDiary.SOURCE_SCHEDULED);
        CharacterDiary impromptu = new CharacterDiary();
        impromptu.setCharacterId(100L);
        impromptu.setSource(CharacterDiary.SOURCE_IMPROMPTU);
        // 只返回即兴：不占定时互斥 → 仍入队
        when(conversationMapper.selectSingleConversationsActiveSince(any(), Mockito.anyInt()))
                .thenReturn(List.of(activity(1L, 10L, 100L, 1)));
        when(diaryMapper.selectList(any())).thenReturn(List.of(impromptu));
        when(diaryMapper.selectLastDiaryDates(anyList())).thenReturn(List.of());
        assertEquals(1, service.generateDiaries());

        // 含定时：跳过
        when(diaryMapper.selectList(any())).thenReturn(List.of(impromptu, scheduled));
        assertEquals(0, service.generateDiaries());
    }

    @Test
    void generate_globalCapRespected_forcedFirst() {
        stubCandidateQueries(List.of(
                activity(1L, 10L, 100L, 1),
                activity(2L, 11L, 200L, 1),
                activity(3L, 12L, 300L, 1)));
        ReflectionTestUtils.setField(service, "maxPerRun", 2);

        assertEquals(2, service.generateDiaries());
        verify(aiBackgroundPublisher, Mockito.times(2)).publish(any(AiBackgroundTask.class));
    }

    @Test
    void generate_perUserCapRespected() {
        stubCandidateQueries(List.of(
                activity(1L, 10L, 100L, 5),
                activity(2L, 10L, 200L, 3)));
        ReflectionTestUtils.setField(service, "maxPerUserPerRun", 1);

        assertEquals(1, service.generateDiaries());
        verify(aiBackgroundPublisher, Mockito.times(1)).publish(any(AiBackgroundTask.class));
    }

    // ===== tryGenerateDiary：来源互斥 + 今天零消息门槛 + 禁工具 =====

    private void stubTryHappyPath() {
        Character character = new Character();
        character.setId(100L);
        character.setName("小雪");
        when(characterMapper.selectById(100L)).thenReturn(character);
        when(diaryMapper.selectCount(any())).thenReturn(0L);
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId(10L);
        conv.setCharacterId(100L);
        conv.setMode("SINGLE");
        when(conversationMapper.selectOne(any())).thenReturn(conv);
        when(messageMapper.selectCount(any())).thenReturn(3L);

        List<Message> today = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Message m = new Message();
            m.setSeq((long) (i + 1));
            m.setRole(i % 2 == 0 ? "USER" : "ASSISTANT");
            m.setContent("今天聊到的细节 " + i);
            today.add(m);
        }
        when(messageMapper.selectList(any())).thenReturn(today);

        when(outputLanguageService.resolveForUser(10L)).thenReturn("zh");
        when(chatTurnFacade.assembleSystemPrompt(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("SYS");
        VaultEntryResponse vault = new VaultEntryResponse();
        vault.setProvider("deepseek");
        vault.setModelDefault("deepseek-chat");
        when(apiKeyVaultService.resolvePreferredUserVault(10L)).thenReturn(vault);

        String diaryJson = "{\"title\":\"火锅\",\"content\":\"" + "今天一起聊了火锅的事，" .repeat(10) + "\"}";
        when(aiChatService.chatBlocking(anyLong(), any())).thenReturn(
                ChatResult.builder().content(diaryJson).build());

    }

    @Test
    void tryGenerate_scheduled_happyPath_persistsWithScheduledSource_andDisablesTools() {
        stubTryHappyPath();
        CharacterDiary diary = service.tryGenerateDiary(10L, 100L, CharacterDiary.SOURCE_SCHEDULED);
        assertNotNull(diary);
        assertEquals(CharacterDiary.SOURCE_SCHEDULED, diary.getSource());
        verify(diaryMapper).insert(any(CharacterDiary.class));
        verify(notificationService).notifyDiaryNew(anyLong(), anyLong(), anyLong(), any(), any());

        org.mockito.ArgumentCaptor<com.lianyu.service.dto.AiChatRequest> reqCaptor =
                org.mockito.ArgumentCaptor.forClass(com.lianyu.service.dto.AiChatRequest.class);
        verify(aiChatService).chatBlocking(anyLong(), reqCaptor.capture());
        assertTrue(reqCaptor.getValue().isDisableChatTools());
        // 素材须包含今天的对话
        String userContent = reqCaptor.getValue().getMessages().get(1).getContent();
        assertTrue(userContent.contains("今天（"));
        assertTrue(userContent.contains("今天聊到的细节"));
    }

    @Test
    void tryGenerate_impromptu_quotaReached_returnsNull() {
        stubTryHappyPath();
        when(diaryMapper.selectCount(any())).thenReturn(1L); // 当天已有 1 篇即兴 = 配额满

        assertNull(service.tryGenerateDiary(10L, 100L, CharacterDiary.SOURCE_IMPROMPTU));
        verify(diaryMapper, never()).insert(any(CharacterDiary.class));
    }

    @Test
    void tryGenerate_noMessagesToday_returnsNull() {
        stubTryHappyPath();
        when(messageMapper.selectCount(any())).thenReturn(0L);

        assertNull(service.tryGenerateDiary(10L, 100L, CharacterDiary.SOURCE_SCHEDULED));
        verify(diaryMapper, never()).insert(any(CharacterDiary.class));
    }

    @Test
    void tryGenerate_nullSource_defaultsToScheduled() {
        stubTryHappyPath();
        CharacterDiary diary = service.tryGenerateDiary(10L, 100L, null);
        assertNotNull(diary);
        assertEquals(CharacterDiary.SOURCE_SCHEDULED, diary.getSource());
    }

    // ===== processDiaryJob 透传 source =====

    @Test
    void processJob_passesSourceThrough() {
        CharacterDiaryService spyService = Mockito.spy(service);
        AiBackgroundTask task = AiBackgroundTask.characterDiaryImpromptu(10L, 100L);
        Mockito.doReturn(null).when(spyService).tryGenerateDiary(10L, 100L, CharacterDiary.SOURCE_IMPROMPTU);
        spyService.processDiaryJob(task);
        verify(spyService).tryGenerateDiary(10L, 100L, CharacterDiary.SOURCE_IMPROMPTU);
    }
}
