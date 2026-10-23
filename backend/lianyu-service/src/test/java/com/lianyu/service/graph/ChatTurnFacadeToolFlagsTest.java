package com.lianyu.service.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.agent.AgentImagePort;
import com.lianyu.service.agent.ChatTurnOutcome;
import com.lianyu.service.agent.ChatTurnPort;
import com.lianyu.service.agent.ChatTurnRequest;
import com.lianyu.service.agent.PromptAssemblyPort;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import com.lianyu.service.tools.ToolManager;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 角色级「操控电脑」闸门的**装配**验证。
 *
 * <p>`ToolManager` 自己的单测只证明「两个闸门相与」；这里补的是另一半：
 * `ChatTurnFacade` 真的把 `character` 传下去了，并且两个闸门都与桌面在线状态联动。
 * 这个测试直接抓发给 Python 的 `ChatTurnRequest.toolFlags` —— 也就是模型最终看到的能力开关。
 */
class ChatTurnFacadeToolFlagsTest {

    private ChatTurnPort chatTurnPort;
    private PromptAssemblyPort promptAssemblyPort;
    private AgentImagePort agentImagePort;
    private OutputLanguageService outputLanguageService;
    private TimeTool timeTool;
    private CharacterChatBehaviorResolver behaviorResolver;
    private ToolManager toolManager;
    private AgentBridgeService agentBridgeService;
    private ChatTurnFacade facade;

    @BeforeEach
    void setUp() {
        chatTurnPort = mock(ChatTurnPort.class);
        promptAssemblyPort = mock(PromptAssemblyPort.class);
        agentImagePort = mock(AgentImagePort.class);
        outputLanguageService = mock(OutputLanguageService.class);
        timeTool = mock(TimeTool.class);
        behaviorResolver = mock(CharacterChatBehaviorResolver.class);
        agentBridgeService = mock(AgentBridgeService.class);

        toolManager = new ToolManager(agentBridgeService);
        org.springframework.test.util.ReflectionTestUtils.setField(toolManager, "chatToolsEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(toolManager, "memoryAgenticEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(toolManager, "recentActivityEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(toolManager, "writeDiaryEnabled", true);

        facade = new ChatTurnFacade(chatTurnPort, promptAssemblyPort, agentImagePort,
                outputLanguageService, timeTool, toolManager, behaviorResolver);

        when(outputLanguageService.resolveForRequest(anyLong(), anyString())).thenReturn("zh");
        when(timeTool.readCurrentTimeFact()).thenReturn("2026-10-23 20:00 星期四");
        when(behaviorResolver.resolve(any())).thenReturn(
                new CharacterChatBehavior(3, true, 60, 0.5, 55, 65, "gentle"));
        when(chatTurnPort.invoke(any())).thenAnswer((inv) -> new ChatTurnOutcome(
                "sys", List.of(), "好呀", 10, 5, 15));
    }

    private Character character(boolean desktopEnabled) {
        Character character = new Character();
        character.setId(57L);
        character.setName("验证角色");
        character.setPromptTemplate("你是验证角色。");
        Map<String, Object> settings = new HashMap<>();
        settings.put(CharacterPreferenceResolver.KEY_DESKTOP_ENABLED, desktopEnabled);
        character.setSettings(settings);
        return character;
    }

    private Map<String, Object> captureFlags(Character character) {
        facade.invokeBlocking(ChatTurnCommand.builder()
                .scene(ChatTurnScene.SINGLE)
                .userId(25L)
                .conversationId(59L)
                .character(character)
                .rawUserText("在吗")
                .modelUserText("在吗")
                .build());
        ArgumentCaptor<ChatTurnRequest> captor = ArgumentCaptor.forClass(ChatTurnRequest.class);
        org.mockito.Mockito.verify(chatTurnPort).invoke(captor.capture());
        return captor.getValue().getToolFlags();
    }

    @Test
    void desktopOnlineAndCharacterOptedInGivesComputerFlag() {
        when(agentBridgeService.availableTools(25L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("desk_click", "d", "{}", false)));

        assertThat(captureFlags(character(true))).containsEntry("computer", true);
    }

    @Test
    void characterOptOutWinsEvenWhenDesktopIsOnline() {
        when(agentBridgeService.availableTools(25L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("desk_click", "d", "{}", false)));

        assertThat(captureFlags(character(false))).containsEntry("computer", false);
    }

    @Test
    void characterOptInIsNotEnoughWhenDesktopIsOffline() {
        when(agentBridgeService.availableTools(25L)).thenReturn(List.of());

        assertThat(captureFlags(character(true))).containsEntry("computer", false);
    }

    @Test
    void legacyCharacterWithoutTheKeyStaysOff() {
        when(agentBridgeService.availableTools(25L)).thenReturn(List.of(
                new AgentBridgeService.ClientTool("desk_click", "d", "{}", false)));
        Character legacy = character(true);
        legacy.setSettings(new HashMap<>());

        assertThat(captureFlags(legacy)).containsEntry("computer", false);
    }
}
