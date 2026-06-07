package com.lianyu.service.moments;

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
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

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

    @Value("${lianyu.moments.comments.peer-pick-count:3}")
    private int peerPickCount;

    @Value("${lianyu.moments.comments.followup-peer-probability:0.65}")
    private double followupPeerProbability;

    @Value("${lianyu.moments.comments.peer-guaranteed-min:1}")
    private int peerGuaranteedMin;

    @Value("${lianyu.moments.comments.content-max-chars:120}")
    private int commentMaxChars;

    @Value("${lianyu.moments.comments.peer-first-delay-min-seconds:60}")
    private int peerFirstDelayMinSec;

    @Value("${lianyu.moments.comments.peer-first-delay-max-seconds:180}")
    private int peerFirstDelayMaxSec;

    /** 多位路人评论之间的间隔（秒），避免扎堆 */
    @Value("${lianyu.moments.comments.peer-stagger-min-seconds:60}")
    private int peerStaggerMinSec;

    @Value("${lianyu.moments.comments.peer-stagger-max-seconds:180}")
    private int peerStaggerMaxSec;

    /** 发帖人回复评论前的等待（秒），模拟不是秒回 */
    @Value("${lianyu.moments.comments.author-reply-min-seconds:60}")
    private int authorReplyMinSec;

    @Value("${lianyu.moments.comments.author-reply-max-seconds:180}")
    private int authorReplyMaxSec;

    @Value("${lianyu.moments.comments.peer-retry-delay-seconds:300}")
    private int peerRetryDelaySec;

    /**
     * 动态发布后：错峰安排路人评论（与用户无关，不瞬间扎堆）。
     */
    @Async
    public void afterPostCreated(Long postId) {
        schedulePeerRound(postId, false);
    }

    /**
     * 补偿入口：重置路人轮次并重新调度（用于 AI 失败或过早标记 done 的动态）。
     */
    public void reconcilePeerComments(Long postId) {
        resetPeerRoundState(postId);
        schedulePeerRound(postId, true);
    }

    /**
     * 有人评论后：仅安排发帖人延迟回复，不再因用户评论触发路人跟评。
     */
    @Async
    public void afterCommentAdded(Long postId, Long triggerCommentId) {
        scheduleAuthorReply(postId, triggerCommentId);
    }

    private void schedulePeerRound(Long postId, boolean fromReconcile) {
        MomentsPost post = momentsPostMapper.selectById(postId);
        if (post == null) {
            return;
        }

        if (hasPeerCommentFromOtherCharacter(post)) {
            return;
        }

        MomentsInteractionState state = interactionStateMapper.selectById(post.getId());
        if (state != null && state.getPeerRoundDone() != null && state.getPeerRoundDone() == 1) {
            if (hasPeerCommentFromOtherCharacter(post)) {
                return;
            }
            resetPeerRoundState(postId);
            state = interactionStateMapper.selectById(post.getId());
        }

        List<Character> candidates = listPeerCandidates(post);
        if (candidates.isEmpty()) {
            int minChars = isUserPost(post) ? 1 : 2;
            if (countOwnedCharacters(post.getUserId()) >= minChars) {
                log.warn("Moments peer round: no available characters (blocked/DND?), will retry: postId={}",
                        postId);
                schedulePeerRoundRetry(postId);
                return;
            }
            log.info("Moments peer round skipped (insufficient characters): postId={}, userId={}",
                    postId, post.getUserId());
            markPeerRoundDone(post, List.of());
            return;
        }

        Collections.shuffle(candidates);
        List<Character> picked = pickPeerCharacters(candidates);
        if (picked.isEmpty()) {
            markPeerRoundDone(post, List.of());
            return;
        }
        List<Long> pickedIds = picked.stream().map(Character::getId).toList();
        savePeerRoundPending(post, pickedIds);

        long firstDelayMs = fromReconcile
                ? randomSeconds(15, 45) * 1000L
                : randomSeconds(peerFirstDelayMinSec, peerFirstDelayMaxSec) * 1000L;
        schedulePeerCommentChain(postId, pickedIds, 0, firstDelayMs, 0);
        log.info("Moments peer round scheduled: postId={}, peers={}, firstDelaySec={}, reconcile={}",
                postId, pickedIds, firstDelayMs / 1000, fromReconcile);
    }

    /**
     * 链式调度路人评论：上一位评论完成后再等 {@link #peerStaggerMinSec}~{@link #peerStaggerMaxSec} 秒才安排下一位。
     */
    private void schedulePeerCommentChain(Long postId, List<Long> peerIds, int index, long delayMs, int successCount) {
        if (index >= peerIds.size()) {
            finalizePeerRound(postId, successCount, peerIds);
            return;
        }
        Long peerId = peerIds.get(index);
        scheduledExecutorService.schedule(() -> {
            int updatedSuccess = successCount;
            if (executePeerComment(postId, peerId)) {
                updatedSuccess++;
            }
            if (index + 1 < peerIds.size()) {
                long staggerMs = randomSeconds(peerStaggerMinSec, peerStaggerMaxSec) * 1000L;
                schedulePeerCommentChain(postId, peerIds, index + 1, staggerMs, updatedSuccess);
            } else {
                finalizePeerRound(postId, updatedSuccess, peerIds);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        log.debug("Moments peer comment scheduled: postId={}, peerIndex={}, delayMs={}",
                postId, index, delayMs);
    }

    private void finalizePeerRound(Long postId, int successCount, List<Long> pickedIds) {
        MomentsPost post = momentsPostMapper.selectById(postId);
        if (post == null) {
            return;
        }
        if (successCount > 0 || hasPeerCommentFromOtherCharacter(post)) {
            markPeerRoundDone(post, pickedIds);
            return;
        }
        log.warn("Moments peer round produced zero comments: postId={}, will retry", postId);
        resetPeerRoundState(postId);
        long retryMs = Math.max(60, peerRetryDelaySec) * 1000L;
        scheduledExecutorService.schedule(
                () -> schedulePeerRound(postId, true),
                retryMs,
                TimeUnit.MILLISECONDS);
    }

    private boolean executePeerComment(Long postId, Long peerCharacterId) {
        if (!tryLock(postId)) {
            return false;
        }
        try {
            MomentsPost post = momentsPostMapper.selectById(postId);
            if (post == null) {
                return false;
            }
            if (hasPeerCommentFromOtherCharacter(post)) {
                return true;
            }

            String idem = "peer:" + post.getId() + ":" + peerCharacterId;
            if (existsIdempotency(idem)) {
                return true;
            }

            Character peer = characterMapper.selectById(peerCharacterId);
            if (peer == null || isBlocked(peer) || isDoNotDisturbActive(peer)) {
                return false;
            }

            Character postAuthor = post.getCharacterId() != null
                    ? characterMapper.selectById(post.getCharacterId())
                    : null;
            boolean userPost = isUserPost(post);
            String postAuthorName = userPost ? "用户" : (postAuthor != null ? postAuthor.getName() : "发帖角色");

            String instruction;
            if (userPost) {
                instruction = String.format("""
                        用户刚发了一条朋友圈（不是你的用户专属对话，而是公开动态）：
                        「%s」
                        请以你的身份写一条评论：像朋友随口回一句，可以自然称呼用户。
                        15~60字，不要重复原文，不要话题标签，不要解释自己是AI。
                        """, trim(post.getContent(), 200));
            } else {
                instruction = String.format("""
                        另一位角色「%s」刚发了一条朋友圈（不是你的用户，也不是你在对主人说话）：
                        「%s」
                        请以你的身份，对「%s」这条动态写评论：像同行随口回一句，可以直呼对方名字。
                        禁止：对用户/主人/Darling 说话；不要使用你专指用户的昵称；不要写「你来找我」这类明显在喊用户的句子。
                        15~60字，不要重复原文，不要话题标签，不要解释自己是AI。
                        """, postAuthorName, trim(post.getContent(), 200), postAuthorName);
            }

            String content = generateCharacterComment(
                    post.getUserId(),
                    peer,
                    post,
                    userPost
                            ? MomentsCommentAudience.user("用户")
                            : MomentsCommentAudience.character(postAuthorName),
                    instruction
            );
            if (content == null) {
                return false;
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
                if (!userPost) {
                    scheduleAuthorReply(postId, saved.getId());
                }
                return true;
            }
            return false;
        } finally {
            unlock(postId);
        }
    }

    private void scheduleAuthorReply(Long postId, Long triggerCommentId) {
        scheduleAuthorReply(postId, triggerCommentId, 0);
    }

    private void scheduleAuthorReply(Long postId, Long triggerCommentId, int attempt) {
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
                boolean ok = runAuthorReply(post, trigger);
                if (!ok && attempt < 1) {
                    scheduleAuthorReply(postId, triggerCommentId, attempt + 1);
                }
            } finally {
                unlock(postId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        log.debug("Moments author reply scheduled: postId={}, triggerCommentId={}, delaySec={}, attempt={}",
                postId, triggerCommentId, delayMs / 1000, attempt);
    }

    private boolean runAuthorReply(MomentsPost post, MomentsComment trigger) {
        Long authorId = post.getCharacterId();
        if (authorId == null) {
            return true;
        }
        if (MomentsCommentService.AUTHOR_CHARACTER.equals(trigger.getAuthorType())
                && authorId.equals(trigger.getCharacterId())) {
            return true;
        }

        String idem = "author-reply:" + trigger.getId();
        if (existsIdempotency(idem)) {
            return true;
        }

        Character author = characterMapper.selectById(authorId);
        if (author == null || isBlocked(author) || isDoNotDisturbActive(author)) {
            return false;
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
            return false;
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
            return true;
        }
        return false;
    }

    /**
     * 至少保证 {@link #peerGuaranteedMin} 个角色评论；额外角色按 {@link #followupPeerProbability} 概率加入。
     */
    private List<Character> pickPeerCharacters(List<Character> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        int guaranteed = Math.min(Math.max(1, peerGuaranteedMin), candidates.size());
        int maxPick = Math.min(Math.max(guaranteed, peerPickCount), candidates.size());
        List<Character> picked = new ArrayList<>();
        for (int i = 0; i < candidates.size() && picked.size() < maxPick; i++) {
            if (picked.size() < guaranteed) {
                picked.add(candidates.get(i));
            } else if (ThreadLocalRandom.current().nextDouble() < followupPeerProbability) {
                picked.add(candidates.get(i));
            }
        }
        return picked;
    }

    private List<Character> listPeerCandidates(MomentsPost post) {
        Long authorId = post.getCharacterId();
        List<Character> all = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, post.getUserId()));
        List<Character> available = all.stream()
                .filter(c -> c.getId() != null)
                .filter(c -> !isUserPost(post) || !c.getId().equals(authorId))
                .filter(c -> !isBlocked(c) && !isDoNotDisturbActive(c))
                .collect(Collectors.toList());
        if (!available.isEmpty()) {
            return available;
        }
        if (isUserPost(post)) {
            return all.stream()
                    .filter(c -> c.getId() != null)
                    .filter(c -> !isBlocked(c))
                    .collect(Collectors.toList());
        }
        return all.stream()
                .filter(c -> c.getId() != null && !c.getId().equals(authorId))
                .filter(c -> !isBlocked(c))
                .collect(Collectors.toList());
    }

    private static boolean isUserPost(MomentsPost post) {
        return post != null && "USER".equalsIgnoreCase(post.getAuthorType());
    }

    private void markPeerRoundDone(MomentsPost post, List<Long> pickedIds) {
        MomentsInteractionState state = interactionStateMapper.selectById(post.getId());
        if (state == null) {
            state = new MomentsInteractionState();
            state.setPostId(post.getId());
            state.setUserId(post.getUserId());
        }
        state.setPeerRoundDone(1);
        state.setPeerRoundSeq((state.getPeerRoundSeq() == null ? 0 : state.getPeerRoundSeq()) + 1);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pickedCharacterIds", pickedIds);
        meta.put("completedAt", LocalDateTime.now().toString());
        state.setLastPeerSampleJson(meta);
        if (interactionStateMapper.selectById(post.getId()) == null) {
            interactionStateMapper.insert(state);
        } else {
            interactionStateMapper.updateById(state);
        }
    }

    private void savePeerRoundPending(MomentsPost post, List<Long> pickedIds) {
        MomentsInteractionState state = interactionStateMapper.selectById(post.getId());
        if (state == null) {
            state = new MomentsInteractionState();
            state.setPostId(post.getId());
            state.setUserId(post.getUserId());
            state.setPeerRoundDone(0);
            state.setPeerRoundSeq(0);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("pickedCharacterIds", pickedIds);
        meta.put("pendingAt", LocalDateTime.now().toString());
        state.setLastPeerSampleJson(meta);
        if (interactionStateMapper.selectById(post.getId()) == null) {
            interactionStateMapper.insert(state);
        } else {
            interactionStateMapper.updateById(state);
        }
    }

    private void resetPeerRoundState(Long postId) {
        MomentsInteractionState state = interactionStateMapper.selectById(postId);
        if (state == null) {
            return;
        }
        state.setPeerRoundDone(0);
        interactionStateMapper.updateById(state);
    }

    private boolean hasPeerCommentFromOtherCharacter(MomentsPost post) {
        if (post == null) {
            return false;
        }
        LambdaQueryWrapper<MomentsComment> qw = new LambdaQueryWrapper<MomentsComment>()
                .eq(MomentsComment::getPostId, post.getId())
                .eq(MomentsComment::getSourceType, MomentsCommentService.SOURCE_AUTO_PEER_COMMENT)
                .isNotNull(MomentsComment::getCharacterId);
        if (!isUserPost(post) && post.getCharacterId() != null) {
            qw.ne(MomentsComment::getCharacterId, post.getCharacterId());
        }
        Long count = momentsCommentMapper.selectCount(qw);
        return count != null && count > 0;
    }

    private long countOwnedCharacters(Long userId) {
        if (userId == null) {
            return 0L;
        }
        Long count = characterMapper.selectCount(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, userId));
        return count == null ? 0L : count;
    }

    private void schedulePeerRoundRetry(Long postId) {
        long retryMs = Math.max(60, peerRetryDelaySec) * 1000L;
        scheduledExecutorService.schedule(
                () -> schedulePeerRound(postId, true),
                retryMs,
                TimeUnit.MILLISECONDS);
    }

    private String generateCharacterComment(Long userId,
                                            Character character,
                                            MomentsPost post,
                                            MomentsCommentAudience audience,
                                            String instruction) {
        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String lang = outputLanguageService.resolveForRequest(userId, null);
        String systemPrompt = promptBuilder.buildSystemPrompt(character, memoryContext, lang, true)
                + buildMomentsSceneGuard(audience, lang)
                + outputLanguageService.buildNaturalStyleBlock(lang);

        AiChatRequest req = new AiChatRequest();
        req.setProvider(AiConstants.PLATFORM_PROVIDER);
        ChatToolContext.bindTo(req, character);
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
        return MomentsTextSanitizer.sanitize(raw, commentMaxChars, 2);
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
