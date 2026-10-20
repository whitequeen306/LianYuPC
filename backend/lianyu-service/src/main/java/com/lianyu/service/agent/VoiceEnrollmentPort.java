package com.lianyu.service.agent;

/**
 * 自定义音色注册端口（DashScope 音色克隆 enroll / delete）。
 *
 * <p>为什么是端口而不是「客户端」：注册音色是一次**外部 AI 能力调用**，
 * 出站 URL 的拼装、SSRF 白名单、错误脱敏都归 Python。
 * Java 只保留「哪个用户能给哪个角色配音色」「Key 怎么加解密存库」这类产品规则。
 *
 * <p>失败语义：抛 {@code BusinessException(AI_PROVIDER_ERROR, message)}，
 * {@code message} 已由 Python 脱敏并截断，可直接展示给前端。
 */
public interface VoiceEnrollmentPort {

    /**
     * 注册一个音色，返回 voiceId。
     *
     * <p>一次调用只对应一个 {@code targetModel} —— HTTP 与 Realtime 是两个不同的
     * target_model，需要各注册一次（见 {@code CustomVoiceService.enrollDashScope}）。
     *
     * @param userId        调用者（仅用于内网调用的用户一致性校验与日志）
     * @param apiKey        用户自己的 DashScope Key（明文，已解密）
     * @param apiBaseUrl    用户填写的 base（可能为空 → 默认官方域名）
     * @param targetModel   HTTP 或 Realtime 模型名
     * @param preferredName 音色别名（Python 侧会做字符白名单 + 截断）
     * @param audioBytes    已裁剪的 16k 单声道 wav 字节
     */
    String createVoice(long userId,
                       String apiKey,
                       String apiBaseUrl,
                       String targetModel,
                       String preferredName,
                       byte[] audioBytes,
                       String mimeType,
                       String language);

    /** 删除远端音色；**永不抛异常**（删远端失败不该阻塞本地删记录）。 */
    void deleteVoiceQuietly(long userId, String apiKey, String apiBaseUrl, String voiceId);
}
