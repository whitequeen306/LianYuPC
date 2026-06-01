package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.MomentsComment;
import com.lianyu.dao.entity.MomentsInteractionState;
import com.lianyu.dao.entity.MomentsPost;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.MomentsCommentMapper;
import com.lianyu.dao.mapper.MomentsInteractionStateMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MomentsCommentOrchestrator {

    private static final String LOCK_PREFIX = "moments:orchestrate-lock:";

    private final MomentsPostMapper momentsPostMapper;
    private final MomentsCommentMapper momentsCommentMapper;
    private final MomentsInteractionStateMapper interactionStateMapper;
    private final CharacterMapper characterMapper;
    private final MomentsCommentService momentsCommentService;
    private final AiChatService aiChatService;
    private final CharacterPromptBuilder promptBuilder;
    private final MemoryRetriever memoryRetriever;
    private final OutputLanguageService outputLanguageService;
    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService scheduledExecutorService;

    public MomentsCommentOrchestrator(MomentsPostMapper momentsPostMapper,
                                      MomentsCommentMapper momentsCommentMapper,
                                      MomentsInteractionStateMapper interactionStateMapper,
                                      CharacterMapper characterMapper,
                                      @Lazy MomentsCommentService momentsCommentService,
                                      AiChatService aiChatService,
                                      CharacterPromptBuilder promptBuilder,
                                      MemoryRetriever memoryRetriever,
                                      OutputLanguageService outputLanguageService,
                                      StringRedisTemplate redisTemplate,
                                      ScheduledExecutorService scheduledExecutorService) {
        this.momentsPostMapper = momentsPostMapper;
        this.momentsCommentMapper = momentsCommentMapper;
        this.interactionStateMapper = interactionStateMapper;
        this.characterMapper = characterMapper;
        this.momentsCommentService = momentsCommentService;
        this.aiChatService = aiChatService;
        this.promptBuilder = promptBuilder;
        this.memoryRetriever = memoryRetriever;
        this.outputLanguageService = outputLanguageService;
        this.redisTemplate = redisTemplate;
        this.scheduledExecutorService = scheduledExecutorService;
    }

    @Value("${lianyu.moments.comments.peer-pick-count:2}")
    private int peerPickCount;

    @Value("${lianyu.moments.comments.content-max-chars:120}")
    private int commentMaxChars;

    @Value("${lianyu.moments.comments.peer-first-delay-min-seconds:180}")
    private int peerFirstDelayMinSec;

    @Value("${lianyu.moments.comments.peer-first-delay-max-seconds:480}")
    private int peerFirstDelayMaxSec;

    @Value("${lianyu.moments.comments.peer-stagger-min-seconds:120}")
    private int peerStaggerMinSec;

    @Value("${lianyu.moments.comments.peer-stagger-max-seconds:300}")
    private int peerStaggerMaxSec;

    @Value("${lianyu.moments.comments.author-reply-min-seconds:60}")
    private int authorReplyMinSec;

    @Value("${lianyu.moments.comments.author-reply-max-seconds:120}")
    private int authorReplyMaxSec;

    /**
     * 动态发布后：错峰安排路人评论（与用户无关，不瞬间扎堆）。
     */
    @Async
    public void afterPostCreated(Long postId) {
        schedulePeerRound(postId);
    }

    /**
     * 有人评论后：仅安排发帖人延迟回复，不再因用户评论触发路人跟评。
     */
    @Async
    public void afterCommentAdded(Long postId, Long triggerCommentId) {
        scheduleAuthorReply(postId, triggerCommentId);
    }

    private void schedulePeerRound(Long postId) {
        MomentsPost post = momentsPostMapper.selectById(postId);
        if (post == null) {
            return;
        }

        MomentsInteractionState state = interactionStateMapper.selectById(post.getId());
        if (state != null && state.getPeerRoundDone() != null && state.getPeerRoundDone() == 1) {
            return;
        }

        List<Character> candidates = listPeerCandidates(post);
        if (candidates.isEmpty()) {
            markPeerRoundDone(post, List.of());
            return;
        }

        Collections.shuffle(candidates);
        int pick = Math.min(Math.max(1, peerPickCount), candidates.size());
        List<Character> picked = new ArrayList<>(candidates.subList(0, pick));
        List<Long> pickedIds = picked.stream().map(Character::getId).toList();
        markPeerRoundDone(post, pickedIds);

        long delaySec = randomSeconds(peerFirstDelayMinSec, peerFirstDelayMaxSec);
        for (int i = 0; i < picked.size(); i++) {
            Character peer = picked.get(i);
            long delayMs = delaySec * 1000L;
            long peerId = peer.getId();
            scheduledExecutorService.schedule(
                    () -> executePeerComment(postId, peerId),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
            log.debug("Moments peer comment scheduled: postId={}, peer={}, delaySec={}",
                    postId, peer.getName(), delaySec);
            if (i < picked.size() - 1) {
                delaySec += randomSeconds(peerStaggerMinSec, peerStaggerMaxSec);
            }
        }
        log.info("Moments peer round scheduled: postId={}, peers={}, firstDelaySec={}",
                postId, pickedIds, randomSeconds(peerFirstDelayMinSec, peerFirstDelayMaxSec));
    }

    private void executePeerComment(Long postId, Long peerCharacterId) {
        if (!tryLock(postId)) {
            return;
        }
        try {
            MomentsPost post = momentsPostMapper.selectById(postId);
            if (post == null) {
                return;
            }

            String idem = "peer:" + post.getId() + ":" + peerCharacterId;
            if (existsIdempotency(idem)) {
                return;
            }

            Character peer = characterMapper.selectById(peerCharacterId);
            if (peer == null || isBlocked(peer) || isDoNotDisturbActive(peer)) {
                return;
            }

            Character postAuthor = post.getCharacterId() != null
                    ? characterMapper.selectById(post.getCharacterId())
                    : null;
            String postAuthorName = postAuthor != null ? postAuthor.getName() : "发帖角色";

            String content = generateCharacterComment(
                    post.getUserId(),
                    peer,
                    post,
                    MomentsCommentAudience.character(postAuthorName),
                    String.format("""
                            另一位角色「%s」刚发了一条朋友圈（不是你的用户，也不是你在对主人说话）：
                            「%s」
                            请以你的身份，对「%s」这条动态写评论：像同行随口回一句，可以直呼对方名字。
                            禁止：对用户/主人/Darling 说话；不要使用你专指用户的昵称；不要写「你来找我」这类明显在喊用户的句子。
                            15~60字，不要重复原文，不要话题标签，不要解释自己是AI。
                            """, postAuthorName, trim(post.getContent(), 200), postAuthorName)
            );
            if (content == null) {
                return;
            }

            MomentsComment saved = momentsCommentService.insertCharacterComment(
                    post,
                    peer,
                    content,
                    MomentsCommentService.SOURCE_AUTO_PEER_COMMENT,
                    null,
                    null,
                    idem
            );
            if (saved != null) {
                log.info("Moments peer comment: postId={}, peer={}", post.getId(), peer.getName());
                scheduleAuthorReply(postId, saved.getId());
            }
        } finally {
            unlock(postId);
        }
    }

    private void scheduleAuthorReply(Long postId, Long triggerCommentId) {
        long delayMs = randomSeconds(authorReplyMinSec, authorReplyMaxSec) * 1000L;
        scheduledExecutorService.schedule(() -> {
            if (!tryLock(postId)) {
                return;
            }
            try {
                MomentsPost post = momentsPostMapper.selectById(postId);
                MomentsComment trigger = momentsCommentMapper.selectById(triggerCommentId);
                if (post == null || trigger == null) {
                    return;
                }
                runAuthorReply(post, trigger);
            } finally {
                unlock(postId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        log.debug("Moments author reply scheduled: postId={}, triggerCommentId={}, delayMs={}",
                postId, triggerCommentId, delayMs);
    }

    private void runAuthorReply(MomentsPost post, MomentsComment trigger) {
        Long authorId = post.getCharacterId();
        if (authorId == null) {
            return;
        }
        if (MomentsCommentService.AUTHOR_CHARACTER.equals(trigger.getAuthorType())
                && authorId.equals(trigger.getCharacterId())) {
            return;
        }

        String idem = "author-reply:" + trigger.getId();
        if (existsIdempotency(idem)) {
            return;
        }

        Character author = characterMapper.selectById(authorId);
        if (author == null || isBlocked(author) || isDoNotDisturbActive(author)) {
            return;
        }

        String commenterName = resolveCommenterName(trigger);
        boolean commenterIsUser = MomentsCommentService.AUTHOR_USER.equals(trigger.getAuthorType());
        String content = generateCharacterComment(
                post.getUserId(),
                author,
                post,
                commenterIsUser
                        ? MomentsCommentAudience.user(commenterName)
                        : MomentsCommentAudience.character(commenterName),
                commenterIsUser
                        ? String.format("""
                        你在朋友圈发了一条动态，现在「用户」评论了：
                        动态原文：「%s」
                        用户的评论：「%s」
                        请以发帖人身份回复用户这条评论，简短自然，20~80字，不要话题标签，不要解释自己是AI。
                        """, trim(post.getContent(), 200), trim(trigger.getContent(), 200))
                        : String.format("""
                        你在朋友圈发了一条动态，现在另一位角色「%s」评论了（不是用户）：
                        动态原文：「%s」
                        %s 的评论：「%s」
                        请以发帖人身份回复 TA 这条评论；对话对象是 %s，不要对屏幕前的用户说话，也不要把对方当成你的 Darling/主人。
                        20~80字，不要话题标签，不要解释自己是AI。
                        """, commenterName, trim(post.getContent(), 200), commenterName,
                                trim(trigger.getContent(), 200), commenterName)
        );
        if (content == null) {
            return;
        }

        Long rootId = trigger.getRootId() != null ? trigger.getRootId() : trigger.getId();
        MomentsComment saved = momentsCommentService.insertCharacterComment(
                post,
                author,
                content,
                MomentsCommentService.SOURCE_AUTO_AUTHOR_REPLY,
                trigger.getId(),
                rootId,
                idem
        );
        if (saved != null) {
            log.info("Moments author reply: postId={}, triggerCommentId={}", post.getId(), trigger.getId());
        }
    }

    private List<Character> listPeerCandidates(MomentsPost post) {
        Long authorId = post.getCharacterId();
        List<Character> all = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, post.getUserId()));
        return all.stream()
                .filter(c -> c.getId() != null && !c.getId().equals(authorId))
                .filter(c -> !isBlocked(c) && !isDoNotDisturbActive(c))
                .collect(Collectors.toList());
    }

    private void markPeerRoundDone(MomentsPost post, List<Long> pickedIds) {
        MomentsInteractionState state = interactionStateMapper.selectById(post.getId());
        if (state == null) {
            state = new MomentsInteractionState();
            state.setPostId(post.getId());
            state.setUserId(post.getUserId());
            state.setPeerRoundDone(1);
            state.setPeerRoundSeq(1);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("pickedCharacterIds", pickedIds);
            state.setLastPeerSampleJson(meta);
            interactionStateMapper.insert(state);
        } else {
            state.setPeerRoundDone(1);
            state.setPeerRoundSeq((state.getPeerRoundSeq() == null ? 0 : state.getPeerRoundSeq()) + 1);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("pickedCharacterIds", pickedIds);
            state.setLastPeerSampleJson(meta);
            interactionStateMapper.updateById(state);
        }
    }

    private String generateCharacterComment(Long userId,
                                            Character character,
                                            MomentsPost post,
                                            MomentsCommentAudience audience,
                                            String instruction) {
        String memoryContext = memoryRetriever.retrieveContext(character.getId(), userId, post.getContent());
        String lang = outputLanguageService.resolveForRequest(userId, null);
        String systemPrompt = promptBuilder.buildSystemPrompt(character, memoryContext, lang)
                + buildMomentsSceneGuard(audience, lang)
                + outputLanguageService.buildNaturalStyleBlock(lang);

        AiChatRequest req = new AiChatRequest();
        req.setProvider(AiConstants.PLATFORM_PROVIDER);
        List<MessageDto> messages = new ArrayList<>();
        MessageDto sys = new MessageDto();
        sys.setRole("system");
        sys.setContent(systemPrompt);
        messages.add(sys);
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent(instruction);
        messages.add(user);
        req.setMessages(messages);

        try {
            ChatResult result = aiChatService.chatBlocking(userId, req);
            return sanitize(result.getContent());
        } catch (Exception e) {
            log.debug("Moments comment AI failed: character={}, reason={}", character.getName(), e.getMessage());
            return null;
        }
    }

    private String buildMomentsSceneGuard(MomentsCommentAudience audience, String lang) {
        if (audience.addressingUser()) {
            return switch (com.lianyu.common.i18n.OutputLanguage.fromCode(lang)) {
                case EN -> "\n\n[Moment scene] You are replying to the human user's comment on your post. The user is your chat partner.";
                case JA -> "\n\n【朋友圈】あなたの投稿への「ユーザー」コメントへの返信。相手は人間ユーザー。";
                case ZH_TW -> "\n\n【朋友圈場景】你在回覆「用戶」對你所發動態的評論；對方是人類用戶，可以自然稱呼。";
                default -> "\n\n【朋友圈场景】你在回复「用户」对你所发动态的评论；对方是人类用户，可以自然称呼。";
            };
        }
        String target = audience.counterpartyName();
        return switch (com.lianyu.common.i18n.OutputLanguage.fromCode(lang)) {
            case EN -> "\n\n[Moment scene — character only] You are talking to another character \""
                    + target + "\", NOT the human user. Do not use pet names you reserve for the user "
                    + "(e.g. Darling). Address \"" + target + "\" by name or natural second person toward them.";
            case JA -> "\n\n【朋友圈・キャラ同士】相手は別キャラ「" + target + "」で、ユーザーではない。"
                    + "ユーザー専用の呼び方（Darling 等）は使わない。投稿者の名前で話す。";
            case ZH_TW -> "\n\n【朋友圈場景·角色互動】你正在對另一角色「" + target + "」說話，不是對螢幕前的用戶。"
                    + "禁止把單聊裡專指用戶的暱稱（如 Darling、主人、寶貝）用在本次評論；請用「" + target + "」稱呼對方。";
            default -> "\n\n【朋友圈场景·角色互动】你正在对另一角色「" + target + "」说话，不是对屏幕前的用户。"
                    + "禁止把单聊里专指用户的昵称（如 Darling、主人、宝贝）用在本次评论；请用「" + target + "」称呼对方。";
        };
    }

    private record MomentsCommentAudience(String counterpartyName, boolean addressingUser) {
        static MomentsCommentAudience user(String label) {
            return new MomentsCommentAudience(label, true);
        }

        static MomentsCommentAudience character(String name) {
            return new MomentsCommentAudience(name, false);
        }
    }

    private String resolveCommenterName(MomentsComment trigger) {
        if (MomentsCommentService.AUTHOR_USER.equals(trigger.getAuthorType())) {
            return "用户";
        }
        if (trigger.getCharacterId() != null) {
            Character c = characterMapper.selectById(trigger.getCharacterId());
            return c != null ? c.getName() : "另一位角色";
        }
        return "对方";
    }

    private boolean existsIdempotency(String key) {
        Long count = momentsCommentMapper.selectCount(new LambdaQueryWrapper<MomentsComment>()
                .eq(MomentsComment::getIdempotencyKey, key));
        return count != null && count > 0;
    }

    private boolean tryLock(Long postId) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(
                LOCK_PREFIX + postId, "1", Duration.ofSeconds(90));
        return Boolean.TRUE.equals(ok);
    }

    private void unlock(Long postId) {
        redisTemplate.delete(LOCK_PREFIX + postId);
    }

    private static int randomSeconds(int minSec, int maxSec) {
        int lo = Math.max(0, minSec);
        int hi = Math.max(lo, maxSec);
        if (lo == hi) {
            return lo;
        }
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim().replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ");
        if (t.length() > commentMaxChars) {
            t = t.substring(0, commentMaxChars);
        }
        return t.length() < 2 ? "" : t;
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private boolean isBlocked(Character character) {
        if (character == null || character.getSettings() == null) {
            return false;
        }
        Object raw = character.getSettings().get("blocked");
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }

    private boolean isDoNotDisturbActive(Character character) {
        if (character == null || character.getSettings() == null) {
            return false;
        }
        Map<String, Object> settings = character.getSettings();
        if (!booleanSetting(settings, "doNotDisturbEnabled", false)) {
            return false;
        }
        int start = intSetting(settings, "dndStartMinutes", 23 * 60);
        int end = intSetting(settings, "dndEndMinutes", 8 * 60);
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        if (start == end) {
            return true;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    private boolean booleanSetting(Map<String, Object> settings, String key, boolean fallback) {
        Object raw = settings.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private int intSetting(Map<String, Object> settings, String key, int fallback) {
        Object raw = settings.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
