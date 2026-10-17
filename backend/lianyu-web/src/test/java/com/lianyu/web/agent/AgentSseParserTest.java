package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lianyu.web.agent.AgentSseParser.Event;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SSE 帧解析测试。
 *
 * <p>为什么单独测：这是跨语言契约的唯一消费点。解析错一个转义，
 * 用户就会看到缺字/串字；把 [DONE] 当内容转发，客户端会当场断流。
 */
class AgentSseParserTest {

    @Test
    void parsesContentDelta() {
        Event event = AgentSseParser.parseLine("data: {\"content\": \"你好\"}");
        assertEquals(Event.Type.CONTENT, event.type());
        assertEquals("你好", event.text());
    }

    @Test
    void parsesReplaceFrame() {
        Event event = AgentSseParser.parseLine("data: {\"replace\": \"整段替换后\"}");
        assertEquals(Event.Type.REPLACE, event.type());
        assertEquals("整段替换后", event.text());
    }

    @Test
    void parsesErrorFrame() {
        Event event = AgentSseParser.parseLine("data: {\"error\": \"对方模型暂时繁忙，请稍后再试\"}");
        assertEquals(Event.Type.ERROR, event.type());
        assertEquals("对方模型暂时繁忙，请稍后再试", event.text());
    }

    @Test
    void parsesDoneFrame() {
        assertEquals(Event.Type.DONE, AgentSseParser.parseLine("data: [DONE]").type());
        assertEquals(Event.Type.DONE, AgentSseParser.parseLine("data:[DONE]").type());
    }

    @Test
    void ignoresHeartbeatAndBlankAndUnknown() {
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine(": connected").type());
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine(": keep-alive").type());
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("").type());
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("   ").type());
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("event: message").type());
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine(null).type());
    }

    @Test
    void ignoresNonJsonPayload() {
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("data: boom").type());
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("data: 123").type());
    }

    @Test
    void ignoresJsonWithoutKnownFields() {
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("data: {\"other\": 1}").type());
        // pieces 不在本层契约内 —— 必须被忽略（切分是 Java 的职责）
        assertEquals(Event.Type.IGNORED,
                AgentSseParser.parseLine("data: {\"pieces\": [\"a\",\"b\"]}").type());
    }

    @Test
    void doesNotTreatNullContentAsContent() {
        assertEquals(Event.Type.IGNORED, AgentSseParser.parseLine("data: {\"content\": null}").type());
    }

    @Test
    void handlesEscapesInContent() {
        Event event = AgentSseParser.parseLine("data: {\"content\": \"a\\nb\\\"c\\\\d\"}");
        assertEquals(Event.Type.CONTENT, event.type());
        assertEquals("a\nb\"c\\d", event.text());
    }

    @Test
    void handlesUnicodeEscape() {
        Event event = AgentSseParser.parseLine("data: {\"content\": \"\\u4f60\\u597d\"}");
        assertEquals("你好", event.text());
    }

    @Test
    void preservesLeadingAndTrailingSpacesInsideString() {
        Event event = AgentSseParser.parseLine("data: {\"content\": \" 空格 \"}");
        assertEquals(" 空格 ", event.text());
    }

    @Test
    void parsesBatchInOrderAndSkipsIgnored() {
        List<Event> events = AgentSseParser.parseAll(List.of(
                ": connected",
                "data: {\"content\": \"你\"}",
                "",
                "data: {\"content\": \"好\"}",
                "data: [DONE]"
        ));
        assertEquals(3, events.size());
        assertEquals(Event.Type.CONTENT, events.get(0).type());
        assertEquals("你", events.get(0).text());
        assertEquals("好", events.get(1).text());
        assertEquals(Event.Type.DONE, events.get(2).type());
    }

    @Test
    void extractStringReturnsNullForMissingField() {
        assertNull(AgentSseParser.extractString("{\"content\": \"x\"}", "error"));
    }
}
