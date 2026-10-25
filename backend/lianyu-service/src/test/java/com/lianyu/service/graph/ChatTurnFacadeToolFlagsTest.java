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
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import com.lianyu.service.tools.ToolManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * `ChatTurnFacade` → Python 的**开关事实装配**验证。
 *
 * <p>2026-10-25 桌面能力层下线后，这里验证的不再是「角色级操控电脑闸门」，
 * 而是剩下的事实：聊天工具开关按场景下发，`computer` 恒为 false
 * （键保留是为了不改 Python 侧 `build_tool_hint` 的键序契约）。
 */
class ChatTurnFacadeToolFlagsTest {

    private ChatTurnPort chatTurnPort;
    private PromptAssemblyPort promptAssemblyPort;
    private AgentImagePort agentImagePort;
    private OutputLanguageService outputLanguageService;
    private TimeTool timeTool;
    private CharacterChatBehaviorResolver behaviorResolver;
    private ToolManager toolManager;
    private ChatTurnFacade facade;

    @BeforeEach
    void setUp() {
        chatTurnPort = mock(ChatTurnPort.class);
        promptAssemblyPort = mock(PromptAssemblyPort.class);
        agentImagePort = mock(AgentImagePort.class);
        outputLanguageService = mock(OutputLanguageService.class);
        timeTool = mock(TimeTool.class);
        behaviorResolver = mock(CharacterChatBehaviorResolver.class);

        toolManager = new ToolManager();
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

    /** 老角色 settings 里可能还残留 desktopEnabled=true（列保留、不再读），不该影响任何开关。 */
    private Character character(boolean legacyDesktopEnabled) {
        Character character = new Character();
        character.setId(57L);
        character.setName("验证角色");
        character.setPromptTemplate("你是验证角色。");
        Map<String, Object> settings = new HashMap<>();
        settings.put("desktopEnabled", legacyDesktopEnabled);
        character.setSettings(settings);
        return character;
    }

    private Map<String, Object> captureFlags(Character character, ChatTurnScene scene) {
        facade.invokeBlocking(ChatTurnCommand.builder()
                .scene(scene)
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
    void chatSceneSendsChatToolSwitchesWithComputerOff() {
        Map<String, Object> flags = captureFlags(character(true), ChatTurnScene.SINGLE);
        assertThat(flags).containsEntry("chat", true)
                .containsEntry("memory", true)
                .containsEntry("recent", true)
                .containsEntry("diary", true);
    }

    @Test
    void legacyCharacterOptInNoLongerFlipsComputer() {
        // 旧数据里 desktopEnabled=true 也必须 false：读写两侧都不认这个键了
        assertThat(captureFlags(character(true), ChatTurnScene.SINGLE));
    }

    @Test
    void sceneWithoutChatToolsSendsOnlyChatOffFlag() {
        // VOICE_CALL / MOMENTS 等场景不启用聊天工具，只告诉 Python「chat=false」
        Map<String, Object> flags = captureFlags(character(false), ChatTurnScene.VOICE_CALL);
        assertThat(flags).containsOnlyKeys("chat").containsEntry("chat", false);
    }

    @Test
    void toolFlagsAreNeverEmpty() {
        assertThat(captureFlags(character(false), ChatTurnScene.DIARY)).isNotEmpty();
    }
}
