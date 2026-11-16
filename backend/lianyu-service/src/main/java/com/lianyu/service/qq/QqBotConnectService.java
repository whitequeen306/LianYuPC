package com.lianyu.service.qq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.QqBotAccount;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.QqBotAccountMapper;
import com.lianyu.security.util.JasyptUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BYO（用户自带 QQ 官方机器人）的扫码接入。
 *
 * <p>链路：App 点「连接」→ 这里向腾讯要一个 bind task（同时生成一把一次性 AES 钥匙）
 * → App 把二维码画出来 → 用户在手机 QQ 里扫码（可当场新建机器人）→ 我们轮询到
 * COMPLETED，拿到 appId + 加密的 appSecret + 该用户对这个 bot 的 openid
 * → 解密、加密入库 → 用户选角色即完成绑定。
 *
 * <p>为什么不用 6 位绑定码：openid 是按 bot 维度发的，扫码那一刻腾讯已经告诉我们
 * 「谁在说话」，再让用户手打一串数字纯属多余步骤。
 *
 * <p>一次性钥匙只留在本进程内存（{@link #PENDING}）：backend 目前单实例，且钥匙
 * 有效期只有十几分钟；将来多实例时换 Redis 即可，不落库是为了不让它跟着备份走。
 */
@Slf4j
@Service
public class QqBotConnectService {

    private static final String CHANNEL_C2C = "qqbot-c2c";
    private static final Duration TASK_TTL = Duration.ofMinutes(15);
    private static final int MAX_PENDING = 500;

    private final QqBotConnectClient connectClient;
    private final QqBotAccountMapper accountMapper;
    private final CharacterMapper characterMapper;
    private final QqChannelService channelService;
    private final JasyptUtil jasyptUtil;

    private record PendingTask(Long userId, String keyBase64, LocalDateTime createdAt) {
    }

    private final Map<String, PendingTask> PENDING = new ConcurrentHashMap<>();

    public QqBotConnectService(QqBotConnectClient connectClient,
                               QqBotAccountMapper accountMapper,
                               CharacterMapper characterMapper,
                               QqChannelService channelService,
                               JasyptUtil jasyptUtil) {
        this.connectClient = connectClient;
        this.accountMapper = accountMapper;
        this.characterMapper = characterMapper;
        this.channelService = channelService;
        this.jasyptUtil = jasyptUtil;
    }

    // ------------------------------------------------------------ 扫码

    public TaskView startTask(Long userId, String source) {
        prune();
        QqBotConnectClient.BindTask task = connectClient.createBindTask();
        PENDING.put(task.taskId(), new PendingTask(userId, task.keyBase64(), LocalDateTime.now()));
        log.info("QQ bot bind task created: userId={}, taskId={}, pending={}",
                userId, abbreviate(task.taskId()), PENDING.size());
        return new TaskView(task.taskId(), connectClient.connectUrl(task.taskId(), source),
                TASK_TTL.toSeconds());
    }

    /**
     * 轮询扫码结果。返回 status：pending / completed / expired / unknown。
     * 只有 completed 才代表凭据已入库。
     */
    public TaskStatus pollTask(Long userId, String taskId) {
        PendingTask pending = PENDING.get(taskId);
        if (pending == null || !pending.userId().equals(userId)) {
            return new TaskStatus("unknown", null, null, null);
        }
        if (pending.createdAt().plus(TASK_TTL).isBefore(LocalDateTime.now())) {
            PENDING.remove(taskId);
            return new TaskStatus("expired", null, null, null);
        }
        QqBotConnectClient.BindPoll poll;
        try {
            poll = connectClient.pollBindResult(taskId);
        } catch (RuntimeException e) {
            // 腾讯偶发抖动不该把用户的二维码直接判死，交给前端继续轮询
            log.warn("poll bind result failed: taskId={}, err={}", abbreviate(taskId), e.getMessage());
            return new TaskStatus("pending", null, null, null);
        }
        if (poll.expired()) {
            PENDING.remove(taskId);
            return new TaskStatus("expired", null, null, null);
        }
        if (!poll.completed()) {
            return new TaskStatus("pending", null, null, null);
        }

        String secret = connectClient.decryptSecret(poll.encryptSecret(), pending.keyBase64());
        QqBotAccount account = upsertAccount(userId, poll.appId(), secret, poll.openId());
        PENDING.remove(taskId);
        log.info("QQ bot scanned: userId={}, appId={}, openid={}",
                userId, account.getAppId(), mask(account.getExternalOpenid()));
        return new TaskStatus("completed", account.getAppId(), account.getBotName(),
                account.getCharacterId());
    }

    private QqBotAccount upsertAccount(Long userId, String appId, String secret, String openId) {
        QqBotAccount existing = accountMapper.selectOne(new LambdaQueryWrapper<QqBotAccount>()
                .eq(QqBotAccount::getAppId, appId)
                .last("LIMIT 1"));
        String cipher = jasyptUtil.encrypt(secret);
        if (existing == null) {
            QqBotAccount row = new QqBotAccount();
            row.setUserId(userId);
            row.setAppId(appId);
            row.setAppSecretCipher(cipher);
            row.setExternalOpenid(openId);
            row.setStatus("active");
            accountMapper.insert(row);
            return row;
        }
        if (!existing.getUserId().equals(userId)) {
            // 机器人被另一个人绑走：以最新扫码者为准（同一 appId 只能有一个主人）
            log.info("QQ bot re-bound to another user: appId={}, from={}, to={}",
                    appId, existing.getUserId(), userId);
            existing.setUserId(userId);
        }
        existing.setAppSecretCipher(cipher);
        existing.setExternalOpenid(openId);
        existing.setStatus("active");
        existing.setLastError(null);
        accountMapper.updateById(existing);
        return existing;
    }

    // ------------------------------------------------------------ 账号与绑定

    @Transactional(readOnly = true)
    public List<AccountView> listAccounts(Long userId) {
        return accountMapper.selectList(new LambdaQueryWrapper<QqBotAccount>()
                        .eq(QqBotAccount::getUserId, userId)
                        .orderByDesc(QqBotAccount::getUpdatedAt))
                .stream()
                .map(this::toView)
                .toList();
    }

    /** 用户选完角色后调用：写库 + 建/复用会话 + 绑定 openid。 */
    @Transactional
    public AccountView bindCharacter(Long userId, String appId, Long characterId) {
        QqBotAccount account = requireAccount(userId, appId);
        if (account.getExternalOpenid() == null || account.getExternalOpenid().isBlank()) {
            throw new BusinessException(ErrorCode.QQ_BOT_ACCOUNT_NOT_FOUND);
        }
        channelService.bindDirect(CHANNEL_C2C, account.getExternalOpenid(), userId, characterId);
        account.setCharacterId(characterId);
        account.setStatus("active");
        accountMapper.updateById(account);
        log.info("QQ bot bound to character: userId={}, appId={}, characterId={}",
                userId, appId, characterId);
        return toView(account);
    }

    @Transactional
    public boolean removeAccount(Long userId, String appId) {
        QqBotAccount account = accountMapper.selectOne(new LambdaQueryWrapper<QqBotAccount>()
                .eq(QqBotAccount::getUserId, userId)
                .eq(QqBotAccount::getAppId, appId)
                .last("LIMIT 1"));
        if (account == null) {
            return false;
        }
        if (account.getExternalOpenid() != null) {
            channelService.unbindExternal(CHANNEL_C2C, account.getExternalOpenid());
        }
        accountMapper.deleteById(account.getId());
        log.info("QQ bot account removed: userId={}, appId={}", userId, appId);
        return true;
    }

    // ------------------------------------------------------------ 网关视角

    /** 网关启动/轮询时拉取：只给「谁连着、用哪个角色」，不给任何凭据。 */
    @Transactional(readOnly = true)
    public List<InternalAccount> activeAccounts() {
        return accountMapper.selectList(new LambdaQueryWrapper<QqBotAccount>()
                        .eq(QqBotAccount::getStatus, "active")
                        .isNotNull(QqBotAccount::getCharacterId)
                        .orderByAsc(QqBotAccount::getId))
                .stream()
                .map(a -> new InternalAccount(a.getAppId(), a.getExternalOpenid(), a.getCharacterId()))
                .toList();
    }

    /** 网关按 appId 换短期 accessToken；secret 在库里加密存放，不经过网络。 */
    public TokenView issueAccessToken(String appId) {
        QqBotAccount account = accountMapper.selectOne(new LambdaQueryWrapper<QqBotAccount>()
                .eq(QqBotAccount::getAppId, appId)
                .eq(QqBotAccount::getStatus, "active")
                .last("LIMIT 1"));
        if (account == null) {
            throw new BusinessException(ErrorCode.QQ_BOT_ACCOUNT_NOT_FOUND);
        }
        String secret = jasyptUtil.decrypt(account.getAppSecretCipher());
        QqBotConnectClient.AccessToken token = connectClient.fetchAccessToken(appId, secret);
        return new TokenView(token.accessToken(), token.expiresInSeconds());
    }

    public void markError(String appId, String message) {
        QqBotAccount account = accountMapper.selectOne(new LambdaQueryWrapper<QqBotAccount>()
                .eq(QqBotAccount::getAppId, appId)
                .last("LIMIT 1"));
        if (account == null) {
            return;
        }
        account.setLastError(message == null ? null : abbreviate(message));
        accountMapper.updateById(account);
    }

    // ------------------------------------------------------------ 内部结构

    private QqBotAccount requireAccount(Long userId, String appId) {
        QqBotAccount account = accountMapper.selectOne(new LambdaQueryWrapper<QqBotAccount>()
                .eq(QqBotAccount::getUserId, userId)
                .eq(QqBotAccount::getAppId, appId)
                .last("LIMIT 1"));
        if (account == null) {
            throw new BusinessException(ErrorCode.QQ_BOT_ACCOUNT_NOT_FOUND);
        }
        return account;
    }

    private AccountView toView(QqBotAccount account) {
        String characterName = "";
        if (account.getCharacterId() != null) {
            Character character = characterMapper.selectById(account.getCharacterId());
            if (character != null) {
                characterName = character.getName();
            }
        }
        return new AccountView(account.getAppId(), account.getBotName(),
                account.getCharacterId(), characterName,
                mask(account.getExternalOpenid()), account.getStatus(),
                account.getLastError(), account.getCreatedAt());
    }

    private void prune() {
        LocalDateTime cutoff = LocalDateTime.now().minus(TASK_TTL);
        PENDING.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(cutoff));
        if (PENDING.size() > MAX_PENDING) {
            List<String> keys = new ArrayList<>(PENDING.keySet());
            for (String key : keys.subList(0, keys.size() - MAX_PENDING)) {
                PENDING.remove(key);
            }
        }
    }

    private static String mask(String value) {
        if (value == null || value.length() <= 8) {
            return value == null ? "" : "***";
        }
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 64 ? value : value.substring(0, 64) + "…";
    }

    public record TaskView(String taskId, String qrUrl, long ttlSeconds) {
    }

    public record TaskStatus(String status, String appId, String botName, Long characterId) {
    }

    public record AccountView(String appId, String botName, Long characterId, String characterName,
                              String maskedOpenid, String status, String lastError,
                              LocalDateTime createdAt) {
    }

    public record InternalAccount(String appId, String externalOpenid, Long characterId) {
    }

    public record TokenView(String accessToken, long expiresInSeconds) {
    }
}
