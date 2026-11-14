package com.lianyu.service.qq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.QqBindCode;
import com.lianyu.dao.entity.QqChannelBinding;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.QqBindCodeMapper;
import com.lianyu.dao.mapper.QqChannelBindingMapper;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.dto.ConversationResponse;
import com.lianyu.service.dto.CreateConversationRequest;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.SendMessageRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.ai.ApiKeyVaultService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * QQ 渠道绑定：外部身份 → 予念用户/角色/会话。
 *
 * <p>为什么在 Java 而不是网关里：绑定关系是事实数据（要落库、要能被 App 查询和解绑），
 * 而且网关跑在公网，不应该持有用户凭据。网关只带服务凭证打 /internal/qq/*，
 * 身份解析、会话选择、模型选择全部在这里完成。
 */
@Slf4j
@Service
public class QqChannelService {

    /** 绑定码有效期：短到不容易被猜到，长到够用户在 QQ 里打完一句话。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final int CODE_DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final QqChannelBindingMapper bindingMapper;
    private final QqBindCodeMapper codeMapper;
    private final CharacterMapper characterMapper;
    private final ConversationService conversationService;
    private final ApiKeyVaultService apiKeyVaultService;

    public QqChannelService(QqChannelBindingMapper bindingMapper,
                            QqBindCodeMapper codeMapper,
                            CharacterMapper characterMapper,
                            ConversationService conversationService,
                            ApiKeyVaultService apiKeyVaultService) {
        this.bindingMapper = bindingMapper;
        this.codeMapper = codeMapper;
        this.characterMapper = characterMapper;
        this.conversationService = conversationService;
        this.apiKeyVaultService = apiKeyVaultService;
    }

    // ---------------------------------------------------------------- 绑定码

    /**
     * 生成一次性绑定码。同一角色同时只保留一个未消费的码——用户连点两次也不会留下两把钥匙。
     */
    @Transactional
    public BindCodeView issueCode(Long userId, Long characterId) {
        Character character = characterMapper.selectById(characterId);
        if (character == null || userId == null || !userId.equals(character.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        codeMapper.delete(new LambdaQueryWrapper<QqBindCode>()
                .eq(QqBindCode::getUserId, userId)
                .eq(QqBindCode::getCharacterId, characterId)
                .isNull(QqBindCode::getConsumedAt));

        LocalDateTime expiresAt = LocalDateTime.now().plus(CODE_TTL);
        for (int attempt = 0; attempt < 5; attempt++) {
            QqBindCode row = new QqBindCode();
            row.setCode(randomCode());
            row.setUserId(userId);
            row.setCharacterId(characterId);
            row.setExpiresAt(expiresAt);
            try {
                codeMapper.insert(row);
                log.info("QQ bind code issued: userId={}, characterId={}, expiresAt={}",
                        userId, characterId, expiresAt);
                return new BindCodeView(row.getCode(), character.getName(), expiresAt, CODE_TTL.toSeconds());
            } catch (DuplicateKeyException e) {
                log.warn("QQ bind code collision, retrying: attempt={}", attempt + 1);
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "绑定码生成失败，请再试一次");
    }

    /**
     * 用绑定码把外部身份绑到角色上。码只能成功用一次；重复用、过期用都算失败。
     */
    @Transactional
    public BindResult bind(String channel, String externalId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw new BusinessException(ErrorCode.QQ_BIND_CODE_INVALID);
        }
        QqBindCode row = codeMapper.selectOne(new LambdaQueryWrapper<QqBindCode>()
                .eq(QqBindCode::getCode, code)
                .last("LIMIT 1"));
        if (row == null || row.getConsumedAt() != null) {
            throw new BusinessException(ErrorCode.QQ_BIND_CODE_INVALID);
        }
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.QQ_BIND_CODE_EXPIRED);
        }

        // 先抢占消费权：并发下两个 QQ 同时发同一个码，只有一个能 update 成功。
        int consumed = codeMapper.update(null, new LambdaUpdateWrapper<QqBindCode>()
                .eq(QqBindCode::getId, row.getId())
                .isNull(QqBindCode::getConsumedAt)
                .set(QqBindCode::getConsumedAt, LocalDateTime.now())
                .set(QqBindCode::getConsumedChannel, channel)
                .set(QqBindCode::getConsumedExternalId, externalId));
        if (consumed == 0) {
            throw new BusinessException(ErrorCode.QQ_BIND_CODE_INVALID);
        }

        ConversationResponse conversation = conversationService.create(row.getUserId(), singleConversation(row.getCharacterId()));
        QqChannelBinding binding = upsertBinding(channel, externalId, row.getUserId(), row.getCharacterId(), conversation.getId());
        log.info("QQ channel bound: channel={}, externalId={}, userId={}, characterId={}, conversationId={}",
                channel, mask(externalId), binding.getUserId(), binding.getCharacterId(), binding.getConversationId());
        return BindResult.builder()
                .userId(binding.getUserId())
                .characterId(binding.getCharacterId())
                .characterName(conversation.getCharacterName())
                .conversationId(binding.getConversationId())
                .build();
    }

    @Transactional(readOnly = true)
    public List<BindingView> listBindings(Long userId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<QqChannelBinding>()
                        .eq(QqChannelBinding::getUserId, userId)
                        .orderByDesc(QqChannelBinding::getUpdatedAt))
                .stream()
                .map(b -> {
                    Character character = characterMapper.selectById(b.getCharacterId());
                    return BindingView.builder()
                            .id(b.getId())
                            .channel(b.getChannel())
                            .externalId(b.getExternalId())
                            .characterId(b.getCharacterId())
                            .characterName(character == null ? "" : character.getName())
                            .conversationId(b.getConversationId())
                            .updatedAt(b.getUpdatedAt())
                            .build();
                })
                .toList();
    }

    @Transactional
    public boolean unbind(Long userId, Long bindingId) {
        int removed = bindingMapper.delete(new LambdaQueryWrapper<QqChannelBinding>()
                .eq(QqChannelBinding::getId, bindingId)
                .eq(QqChannelBinding::getUserId, userId));
        return removed > 0;
    }

    // ---------------------------------------------------------------- 回合

    /**
     * 网关收到一条 QQ 消息后调这里：解析身份 → 选模型 → 跑一个完整回合 → 返回要发回去的文本。
     */
    public TurnResult turn(String channel, String externalId, String text) {
        QqChannelBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<QqChannelBinding>()
                .eq(QqChannelBinding::getChannel, channel)
                .eq(QqChannelBinding::getExternalId, externalId)
                .last("LIMIT 1"));
        if (binding == null) {
            throw new BusinessException(ErrorCode.QQ_CHANNEL_NOT_BOUND);
        }
        VaultEntryResponse vault = apiKeyVaultService.resolvePreferredUserVault(binding.getUserId());
        if (vault == null) {
            // 用户没有自己的文本模型配置。这里是唯一能提前说人话的地方，
            // 网关会把 message 原样发到 QQ，所以文案要能被终端用户看懂。
            throw new BusinessException(ErrorCode.MODEL_NOT_FOUND,
                    "你还没在予念里配置文本模型，配好之后我就能在这儿回你了");
        }
        SendMessageRequest request = new SendMessageRequest();
        request.setProvider(vault.getProvider());
        request.setModel(vault.getModelDefault());
        request.setContent(text);

        MessageResponse reply = conversationService.sendMessage(binding.getUserId(), binding.getConversationId(), request);
        return TurnResult.builder()
                .conversationId(binding.getConversationId())
                .characterId(binding.getCharacterId())
                .reply(reply == null ? "" : reply.getContent())
                .build();
    }

    // ---------------------------------------------------------------- 内部

    private QqChannelBinding upsertBinding(String channel, String externalId, Long userId,
                                           Long characterId, Long conversationId) {
        QqChannelBinding existing = bindingMapper.selectOne(new LambdaQueryWrapper<QqChannelBinding>()
                .eq(QqChannelBinding::getChannel, channel)
                .eq(QqChannelBinding::getExternalId, externalId)
                .last("LIMIT 1"));
        if (existing == null) {
            QqChannelBinding row = new QqChannelBinding();
            row.setChannel(channel);
            row.setExternalId(externalId);
            row.setUserId(userId);
            row.setCharacterId(characterId);
            row.setConversationId(conversationId);
            bindingMapper.insert(row);
            return row;
        }
        if (!existing.getUserId().equals(userId)) {
            log.info("QQ channel rebind across users: channel={}, externalId={}, from={}, to={}",
                    channel, mask(externalId), existing.getUserId(), userId);
        }
        existing.setUserId(userId);
        existing.setCharacterId(characterId);
        existing.setConversationId(conversationId);
        existing.setUpdatedAt(LocalDateTime.now());
        bindingMapper.updateById(existing);
        return existing;
    }

    private CreateConversationRequest singleConversation(Long characterId) {
        CreateConversationRequest request = new CreateConversationRequest();
        request.setCharacterId(characterId);
        request.setMode("SINGLE");
        return request;
    }

    private static String randomCode() {
        int bound = (int) Math.pow(10, CODE_DIGITS);
        return String.format("%0" + CODE_DIGITS + "d", RANDOM.nextInt(bound));
    }

    /** 日志里不落完整 openid：能定位是哪个号，又不至于泄漏成可滥用标识。 */
    private static String mask(String value) {
        if (value == null || value.length() <= 8) {
            return value;
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    @Data
    @Builder
    public static class BindCodeView {
        private String code;
        private String characterName;
        private LocalDateTime expiresAt;
        private long ttlSeconds;
    }

    @Data
    @Builder
    public static class BindResult {
        private Long userId;
        private Long characterId;
        private String characterName;
        private Long conversationId;
    }

    @Data
    @Builder
    public static class BindingView {
        private Long id;
        private String channel;
        private String externalId;
        private Long characterId;
        private String characterName;
        private Long conversationId;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    public static class TurnResult {
        private Long conversationId;
        private Long characterId;
        private String reply;
    }
}
