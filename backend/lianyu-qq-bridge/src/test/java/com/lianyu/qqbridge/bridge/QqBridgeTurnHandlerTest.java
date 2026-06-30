package com.lianyu.qqbridge.bridge;

import com.lianyu.qqbridge.config.QqBridgeProperties;
import com.lianyu.qqbridge.napcat.NapCatClient;
import com.lianyu.qqbridge.napcat.OneBotMessageEvent;
import com.lianyu.qqbridge.napcat.OneBotModels;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.dto.MessagePageResponse;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.SendMessageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QqBridgeTurnHandler 路由逻辑测试（不连真 QQ / 不起 WS）。
 * <p>
 * 直接调 {@code onMessageEvent}（@Async/@EventListener 在无 Spring 上下文时退化为同步执行），
 * mock 出 ConversationService 与 NapCatClient，断言：过滤、sendMessage 入参、多段回收、回发、异常兜底、截断。
 * 对应实施计划 Step E 的场景 1（路由→send_private_msg 文本正确）与场景 2（异常→兜底）。
 */
class QqBridgeTurnHandlerTest {

    private QqBridgeProperties props;
    private NapCatClient napCat;
    private ConversationService conversationService;
    private QqBridgeTurnHandler handler;

    @BeforeEach
    void setUp() {
        props = new QqBridgeProperties();
        props.getBinding().setLianyuUserId(1L);
        props.getBinding().setConversationId(42L);
        props.getBinding().setProvider("platform");
        props.getReply().setMaxPieceGapMs(0L);
        props.getReply().setFallbackText("兜底");
        napCat = mock(NapCatClient.class);
        conversationService = mock(ConversationService.class);
        handler = new QqBridgeTurnHandler(props, napCat, conversationService);
    }

    // ---- fixtures ----

    private OneBotModels.MessageEvent privateEvent(long qq, String text) {
        return new OneBotModels.MessageEvent(
                "message", "private", null, 555L, qq, null, qq,
                List.of(new OneBotModels.Segment("text", Map.of("text", text))),
                text, new OneBotModels.Sender(qq, "nick", null), 1234567890L);
    }

    private OneBotModels.MessageEvent groupEvent(long groupId, long senderId, long botId, String text, boolean atBot) {
        List<OneBotModels.Segment> segs = new ArrayList<>();
        if (atBot) {
            segs.add(new OneBotModels.Segment("at", Map.of("qq", String.valueOf(botId))));
        }
        segs.add(new OneBotModels.Segment("text", Map.of("text", text)));
        return new OneBotModels.MessageEvent(
                "message", "group", null, 555L, senderId, groupId, botId,
                segs, text, new OneBotModels.Sender(senderId, "nick", null), 1234567890L);
    }

    private static MessageResponse msg(long seq, String role, String content) {
        return MessageResponse.builder().seq(seq).role(role).content(content).build();
    }

    private static MessagePageResponse page(MessageResponse... records) {
        return MessagePageResponse.builder().records(List.of(records)).hasMore(false).build();
    }

    // ---- 场景 1：私聊路由 + 多段全发 ----

    @Test
    void privateMessage_routedToConversation_allPiecesReplied() {
        props.getBinding().setQqUserId(10001L);
        props.getReply().setSendAllPieces(true);

        // 回复前最新一条 seq=9（USER），作为 prevMaxSeq
        when(conversationService.getMessages(eq(1L), eq(42L), isNull(), eq(1)))
                .thenReturn(page(msg(9L, "USER", "旧")));
        // sendMessage 只返回首段，但已持久化全部
        when(conversationService.sendMessage(eq(1L), eq(42L), any()))
                .thenReturn(msg(11L, "ASSISTANT", "回复1"));
        // 事后拉取：seq DESC，含本轮 user(10) + assistant(11,12)
        when(conversationService.getMessages(eq(1L), eq(42L), isNull(), eq(20)))
                .thenReturn(page(msg(12L, "ASSISTANT", "回复2"),
                        msg(11L, "ASSISTANT", "回复1"),
                        msg(10L, "USER", "你好")));

        handler.onMessageEvent(new OneBotMessageEvent(this, privateEvent(10001L, "你好")));

        ArgumentCaptor<SendMessageRequest> req = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(conversationService).sendMessage(eq(1L), eq(42L), req.capture());
        assertEquals("你好", req.getValue().getContent());
        assertEquals("platform", req.getValue().getProvider());

        // 多段按 seq 升序逐条发回，首段不重复
        verify(napCat).sendPrivateMsg(10001L, "回复1");
        verify(napCat).sendPrivateMsg(10001L, "回复2");
        verify(napCat, never()).sendGroupMsg(anyLong(), anyString());
    }

    @Test
    void sendAllPiecesFalse_onlyFirstPieceSent() {
        props.getBinding().setQqUserId(10001L);
        props.getReply().setSendAllPieces(false);

        when(conversationService.getMessages(eq(1L), eq(42L), isNull(), eq(1)))
                .thenReturn(page(msg(9L, "USER", "旧")));
        when(conversationService.sendMessage(eq(1L), eq(42L), any()))
                .thenReturn(msg(11L, "ASSISTANT", "只发首段"));

        handler.onMessageEvent(new OneBotMessageEvent(this, privateEvent(10001L, "你好")));

        verify(napCat).sendPrivateMsg(10001L, "只发首段");
        // 关闭全发时不再事后拉取（collectReplies 提前 return）
        verify(conversationService, never()).getMessages(anyLong(), anyLong(), any(), eq(20));
    }

    // ---- 场景 2：sendMessage 抛异常 → 兜底文案 ----

    @Test
    void sendMessageThrows_sendsFallbackText() {
        props.getBinding().setQqUserId(10001L);
        when(conversationService.getMessages(eq(1L), eq(42L), isNull(), eq(1)))
                .thenReturn(page(msg(9L, "USER", "旧")));
        when(conversationService.sendMessage(eq(1L), eq(42L), any()))
                .thenThrow(new RuntimeException("AI 不可用"));

        handler.onMessageEvent(new OneBotMessageEvent(this, privateEvent(10001L, "你好")));

        verify(napCat).sendPrivateMsg(10001L, "兜底");
        verify(napCat, never()).sendGroupMsg(anyLong(), anyString());
    }

    // ---- 过滤路径 ----

    @Test
    void privateMessage_fromUnboundUser_ignored() {
        props.getBinding().setQqUserId(10001L); // 仅白名单 10001

        handler.onMessageEvent(new OneBotMessageEvent(this, privateEvent(99999L, "你好")));

        verifyNoInteractions(conversationService);
        verify(napCat, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    void groupMessage_withMentionSelf_repliesToGroup() {
        props.getBinding().setQqUserId(0L); // 不限制私聊来源（本例走群）
        props.getBinding().setAllowGroups(List.of(222L));
        props.getReply().setSendAllPieces(false);
        when(napCat.getSelfId()).thenReturn(10001L);
        when(conversationService.getMessages(eq(1L), eq(42L), isNull(), eq(1)))
                .thenReturn(page(msg(9L, "USER", "旧")));
        when(conversationService.sendMessage(eq(1L), eq(42L), any()))
                .thenReturn(msg(11L, "ASSISTANT", "群回复"));

        handler.onMessageEvent(new OneBotMessageEvent(this, groupEvent(222L, 88888L, 10001L, "你好群", true)));

        verify(napCat).sendGroupMsg(222L, "群回复");
        verify(napCat, never()).sendPrivateMsg(anyLong(), anyString());
    }

    @Test
    void groupMessage_withoutMention_ignored() {
        props.getBinding().setAllowGroups(List.of(222L));
        when(napCat.getSelfId()).thenReturn(10001L);

        handler.onMessageEvent(new OneBotMessageEvent(this, groupEvent(222L, 88888L, 10001L, "你好群", false)));

        verifyNoInteractions(conversationService);
        verify(napCat, never()).sendGroupMsg(anyLong(), anyString());
    }

    @Test
    void groupMessage_notInAllowGroups_ignored() {
        props.getBinding().setAllowGroups(List.of(222L));
        when(napCat.getSelfId()).thenReturn(10001L);

        handler.onMessageEvent(new OneBotMessageEvent(this, groupEvent(999L, 88888L, 10001L, "你好", true)));

        verifyNoInteractions(conversationService);
        verify(napCat, never()).sendGroupMsg(anyLong(), anyString());
    }

    @Test
    void nonTextMessage_ignored() {
        props.getBinding().setQqUserId(10001L);
        OneBotModels.MessageEvent ev = new OneBotModels.MessageEvent(
                "message", "private", null, 555L, 10001L, null, 10001L,
                List.of(new OneBotModels.Segment("image", Map.of("url", "http://x"))),
                "", new OneBotModels.Sender(10001L, "nick", null), 1234567890L);

        handler.onMessageEvent(new OneBotMessageEvent(this, ev));

        verifyNoInteractions(conversationService);
        verify(napCat, never()).sendPrivateMsg(anyLong(), anyString());
    }

    // ---- 截断 ----

    @Test
    void replyLongerThanMaxChars_truncated() {
        props.getBinding().setQqUserId(10001L);
        props.getReply().setSendAllPieces(false);
        props.getReply().setMaxPieceChars(50); // sendReplies 用 Math.max(50, maxPieceChars)=50
        String longReply = "a".repeat(60);
        when(conversationService.getMessages(eq(1L), eq(42L), isNull(), eq(1)))
                .thenReturn(page(msg(9L, "USER", "旧")));
        when(conversationService.sendMessage(eq(1L), eq(42L), any()))
                .thenReturn(msg(11L, "ASSISTANT", longReply));

        handler.onMessageEvent(new OneBotMessageEvent(this, privateEvent(10001L, "你好")));

        verify(napCat).sendPrivateMsg(10001L, "a".repeat(50));
    }
}
