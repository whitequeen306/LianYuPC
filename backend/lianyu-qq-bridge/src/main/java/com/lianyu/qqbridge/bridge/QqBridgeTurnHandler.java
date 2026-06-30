package com.lianyu.qqbridge.bridge;

import com.lianyu.common.constant.AiConstants;
import com.lianyu.qqbridge.config.QqBridgeComponent;
import com.lianyu.qqbridge.config.QqBridgeProperties;
import com.lianyu.qqbridge.napcat.NapCatClient;
import com.lianyu.qqbridge.napcat.OneBotMessageEvent;
import com.lianyu.qqbridge.napcat.OneBotModels;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.dto.MessagePageResponse;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.SendMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 消费 {@link OneBotMessageEvent}，把 QQ 消息路由进 LianYu 会话并把角色回复发回 QQ。
 * <p>
 * 单人模式（Phase 1）：私聊按 {@code binding.qq-user-id} 白名单，群聊按 {@code allow-groups} 且需 @本机器人；
 * 全部路由到 {@code binding.lianyu-user-id} 的 {@code binding.conversation-id} 会话，
 * 直接调 {@link ConversationService#sendMessage} 走完整对话链（记忆/关系/长期记忆），不经 HTTP/鉴权层。
 * <p>
 * 异步消费（{@code @Async}）避免阻塞 WS 收帧线程；同一会话用 ReentrantLock 串行化，
 * 防止用户连发两条导致两轮对话交错。所有异常在本方法内吞掉，绝不冒泡断 WS。
 */
@Slf4j
@QqBridgeComponent
@RequiredArgsConstructor
public class QqBridgeTurnHandler {

    private final QqBridgeProperties props;
    private final NapCatClient napCat;
    private final ConversationService conversationService;

    /** 按 conversationId 串行化对话轮次，避免同一会话并发交错。 */
    private final Map<Long, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();

    @Async
    @EventListener
    public void onMessageEvent(OneBotMessageEvent event) {
        OneBotModels.MessageEvent ev = event == null ? null : event.getMessage();
        if (ev == null || !"message".equals(ev.postType())) {
            return;
        }
        QqBridgeProperties.Binding b = props.getBinding();
        if (b.getLianyuUserId() == 0L || b.getConversationId() == 0L) {
            log.warn("QQ bridge received message but binding.lianyu-user-id/conversation-id not configured; skip");
            return;
        }
        ReentrantLock lock = lockFor(b.getConversationId());
        lock.lock();
        try {
            handle(ev, b);
        } catch (Exception e) {
            log.warn("QQ bridge turn handler error: {}", String.valueOf(e), e);
        } finally {
            lock.unlock();
        }
    }

    private void handle(OneBotModels.MessageEvent ev, QqBridgeProperties.Binding b) {
        boolean isPrivate = "private".equals(ev.messageType());
        boolean isGroup = "group".equals(ev.messageType());
        if (!isPrivate && !isGroup) {
            return;
        }

        if (isPrivate) {
            if (b.getQqUserId() != 0L && (ev.userId() == null || ev.userId() != b.getQqUserId())) {
                log.debug("QQ bridge ignore private message from {} (not bound)", ev.userId());
                return;
            }
        } else {
            if (b.getAllowGroups() == null || b.getAllowGroups().isEmpty()
                    || ev.groupId() == null || !b.getAllowGroups().contains(ev.groupId())) {
                return;
            }
            if (!mentionsSelf(ev)) {
                return;
            }
        }

        String text = MessageSegmentExtractor.toPlainText(ev.message(), napCat.getSelfId());
        if (text.isBlank()) {
            log.debug("QQ bridge ignore empty/non-text message from {}", ev.userId());
            return;
        }

        long lianyuUserId = b.getLianyuUserId();
        long conversationId = b.getConversationId();
        long prevMaxSeq = currentMaxSeq(lianyuUserId, conversationId);

        SendMessageRequest req = new SendMessageRequest();
        req.setProvider((b.getProvider() == null || b.getProvider().isBlank())
                ? AiConstants.PLATFORM_PROVIDER : b.getProvider());
        if (b.getModel() != null && !b.getModel().isBlank()) {
            req.setModel(b.getModel());
        }
        req.setContent(text);

        List<String> replies;
        try {
            MessageResponse first = conversationService.sendMessage(lianyuUserId, conversationId, req);
            replies = collectReplies(lianyuUserId, conversationId, prevMaxSeq, first);
        } catch (Exception e) {
            log.warn("QQ bridge conversation turn failed for conv={}: {}", conversationId, String.valueOf(e), e);
            sendFallback(ev, isPrivate, props.getReply().getFallbackText());
            return;
        }
        if (replies.isEmpty()) {
            log.info("QQ bridge turn produced no reply for conv={}", conversationId);
            return;
        }
        sendReplies(ev, replies, isPrivate);
    }

    private boolean mentionsSelf(OneBotModels.MessageEvent ev) {
        long self = napCat.getSelfId();
        if (self == 0L || ev.message() == null) {
            return false;
        }
        String selfStr = Long.toString(self);
        for (OneBotModels.Segment s : ev.message()) {
            if (s != null && "at".equals(s.type()) && s.data() != null
                    && selfStr.equals(String.valueOf(s.data().get("qq")))) {
                return true;
            }
        }
        return false;
    }

    /** 取当前会话最大 seq，作为本轮新消息的下界（用于事后只取本轮 assistant 分片）。 */
    private long currentMaxSeq(long userId, long conversationId) {
        try {
            MessagePageResponse page = conversationService.getMessages(userId, conversationId, null, 1);
            if (page != null && page.getRecords() != null && !page.getRecords().isEmpty()) {
                Long seq = page.getRecords().get(0).getSeq();
                return seq == null ? 0L : seq;
            }
        } catch (Exception e) {
            log.debug("QQ bridge currentMaxSeq lookup failed (conv may be empty): {}", String.valueOf(e));
        }
        return 0L;
    }

    /**
     * {@code sendMessage} 只返回首条分片但会持久化全部分片；这里再拉一轮最新消息，
     * 取 {@code seq > prevMaxSeq} 且 role=ASSISTANT 的全部，按 seq 升序逐条发回。
     * 若关闭 send-all-pieces，则只发首条。
     */
    private List<String> collectReplies(long userId, long conversationId, long prevMaxSeq, MessageResponse first) {
        List<String> out = new ArrayList<>();
        if (first != null && first.getContent() != null && !first.getContent().isBlank()) {
            out.add(first.getContent());
        }
        if (!props.getReply().isSendAllPieces()) {
            return out;
        }
        try {
            MessagePageResponse page = conversationService.getMessages(userId, conversationId, null, 20);
            if (page == null || page.getRecords() == null) {
                return out;
            }
            List<MessageResponse> fresh = page.getRecords().stream()
                    .filter(m -> m.getSeq() != null && m.getSeq() > prevMaxSeq)
                    .filter(m -> "ASSISTANT".equalsIgnoreCase(m.getRole()))
                    .sorted(Comparator.comparing(MessageResponse::getSeq))
                    .toList();
            if (!fresh.isEmpty()) {
                out.clear();
                for (MessageResponse m : fresh) {
                    if (m.getContent() != null && !m.getContent().isBlank()) {
                        out.add(m.getContent());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("QQ bridge collectReplies extra fetch failed, using first only: {}", String.valueOf(e));
        }
        return out;
    }

    private void sendReplies(OneBotModels.MessageEvent ev, List<String> replies, boolean isPrivate) {
        int maxChars = Math.max(50, props.getReply().getMaxPieceChars());
        long gap = Math.max(0, props.getReply().getMaxPieceGapMs());
        for (int i = 0; i < replies.size(); i++) {
            String piece = replies.get(i);
            if (piece.length() > maxChars) {
                // 不在 UTF-16 代理对中间截断：截断点恰落在高代理项（其右侧为低代理项）时
                // 回退一位，避免产生孤儿高代理项致接收方解码异常/丢字
                int cut = maxChars;
                if (cut > 0
                        && Character.isHighSurrogate(piece.charAt(cut - 1))
                        && cut < piece.length()
                        && Character.isLowSurrogate(piece.charAt(cut))) {
                    cut -= 1;
                }
                piece = piece.substring(0, cut);
            }
            if (isPrivate && ev.userId() != null) {
                napCat.sendPrivateMsg(ev.userId(), piece);
            } else if (!isPrivate && ev.groupId() != null) {
                napCat.sendGroupMsg(ev.groupId(), piece);
            }
            if (i < replies.size() - 1 && gap > 0) {
                try {
                    Thread.sleep(gap);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void sendFallback(OneBotModels.MessageEvent ev, boolean isPrivate, String fallbackText) {
        if (fallbackText == null || fallbackText.isBlank()) {
            return;
        }
        if (isPrivate && ev.userId() != null) {
            napCat.sendPrivateMsg(ev.userId(), fallbackText);
        } else if (!isPrivate && ev.groupId() != null) {
            napCat.sendGroupMsg(ev.groupId(), fallbackText);
        }
    }

    private ReentrantLock lockFor(long conversationId) {
        return conversationLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
    }
}
