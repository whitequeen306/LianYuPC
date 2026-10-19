package com.lianyu.service.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 生成 tool hint 的字节级基线（供 Python 复现）。
 *
 * <p>为什么不手抄文案：agent 侧要按完全相同的分支拼出同一段文本，
 * 手抄一定会漂移。这里用 Java 真实执行生成所有开关组合的输出。
 */
class ToolHintGoldenTest {

    private static final Path OUT = Path.of(System.getProperty("user.dir"))
            .resolve("../../backend-agent/app/prompts/tool_hints_golden.json").normalize();

    @Test
    void dumpAllFlagCombinations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode cases = root.putObject("cases");

        for (int mask = 0; mask < 32; mask++) {
            boolean chat = (mask & 1) > 0;
            boolean memory = (mask & 2) > 0;
            boolean recent = (mask & 4) > 0;
            boolean diary = (mask & 8) > 0;
            boolean computer = (mask & 16) > 0;

            ToolManager manager = new ToolManager(
                    Mockito.mock(TimeTool.class),
                    Mockito.mock(WeatherTool.class),
                    Mockito.mock(MemorySearchTool.class),
                    Mockito.mock(RecentActivityTool.class),
                    Mockito.mock(WriteDiaryTool.class),
                    bridge(computer));
            set(manager, "chatToolsEnabled", chat);
            set(manager, "weatherEnabled", true);
            set(manager, "memoryAgenticEnabled", memory);
            set(manager, "recentActivityEnabled", recent);
            set(manager, "writeDiaryEnabled", diary);

            String key = (chat ? "1" : "0") + (memory ? "1" : "0") + (recent ? "1" : "0")
                    + (diary ? "1" : "0") + (computer ? "1" : "0");
            cases.putObject(key).put("hint", manager.buildToolsPromptHint(7L));
        }

        ObjectNode flags = root.putObject("flags");
        flags.put("order", "chat,memory,recent,diary,computer");
        root.put("note", "由 ToolHintGoldenTest 生成；Python 侧必须逐字节复现");

        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                StandardCharsets.UTF_8);
    }

    private static AgentBridgeService bridge(boolean computer) {
        AgentBridgeService service = Mockito.mock(AgentBridgeService.class);
        Mockito.when(service.availableTools(Mockito.anyLong())).thenReturn(
                computer
                        ? List.of(new AgentBridgeService.ClientTool("computer_task", "本机操作", "{}", true))
                        : List.of(new AgentBridgeService.ClientTool("open_url", "打开网页", "{}", false)));
        return service;
    }

    private static void set(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
