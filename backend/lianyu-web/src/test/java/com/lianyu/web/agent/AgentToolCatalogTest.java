package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 工具清单来源的行为契约。
 *
 * <p>这套断言守的是「agent 抖动时模型不能一个工具都没有」：取不到就退到内置 BOOTSTRAP，
 * 否则时间/天气/记忆全都会开始瞎编。
 */
class AgentToolCatalogTest {

    /** 指向一个必然连不上的地址（连接拒绝），模拟 agent 不可用。 */
    private static AgentToolCatalog unreachable() {
        return new AgentToolCatalog(
                new AgentRuntimeProperties("http://127.0.0.1:1"), new ObjectMapper());
    }

    @Test
    void fallsBackToBootstrapWhenAgentUnreachable() {
        AgentToolCatalog catalog = unreachable();
        List<String> tools = catalog.chatTools();
        assertEquals(List.of("get_current_time", "get_weather", "memory_search", "get_my_recent_life"),
                tools, "取不到清单时必须退回内置四件套，不能返回空");
    }

    @Test
    void bootstrapIsNeverEmpty() {
        assertFalse(unreachable().supported().isEmpty());
    }

    @Test
    void invalidateKeepsServingUsableTools() {
        AgentToolCatalog catalog = unreachable();
        catalog.chatTools();
        catalog.invalidate();
        // 失效后再取：仍然拿得到可用的清单（退到 bootstrap），不会变成空
        assertTrue(catalog.supported().contains("memory_search"));
    }

    @Test
    void repeatedCallsDoNotThrowOnBrokenAgent() {
        AgentToolCatalog catalog = unreachable();
        for (int i = 0; i < 5; i++) {
            assertFalse(catalog.chatTools().isEmpty());
        }
    }
}
