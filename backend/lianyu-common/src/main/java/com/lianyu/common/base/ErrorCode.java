package com.lianyu.common.base;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // 通用
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或 Token 已过期"),
    FORBIDDEN(403, "无权限"),
    CONTENT_POLICY_VIOLATION(403, "内容不符合平台规范"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    // 用户 1xxx
    USERNAME_EXISTS(1001, "用户名已存在"),
    WRONG_PASSWORD(1002, "用户名或密码错误"),
    USER_NOT_FOUND(1003, "用户不存在"),
    USER_DISABLED(1004, "用户已被禁用"),
    AUTH_RATE_LIMITED(1005, "登录或注册请求过于频繁"),

    // 角色 2xxx
    CHARACTER_NOT_FOUND(2001, "角色不存在"),
    CHARACTER_LIMIT(2002, "角色数量已达上限"),
    CHARACTER_ALREADY_ADDED(2003, "你已经添加过该角色了哦"),

    // 对话 3xxx
    CONVERSATION_NOT_FOUND(3001, "对话不存在"),
    MESSAGE_NOT_FOUND(3002, "消息不存在"),

    // AI 4xxx
    AI_PROVIDER_ERROR(4001, "AI 服务商调用异常"),
    AI_TIMEOUT(4002, "AI 调用超时"),
    AI_RATE_LIMITED(4003, "AI 调用频率限制"),
    MODEL_NOT_FOUND(4004, "模型不存在"),

    // 文件 5xxx
    FILE_TOO_LARGE(5001, "文件大小超出限制"),
    FILE_TYPE_DENIED(5002, "文件类型不允许"),
    UPLOAD_FAILED(5003, "上传失败"),

    // 安全 6xxx
    API_KEY_ENCRYPT_FAILED(6001, "API Key 加密失败"),
    API_KEY_DECRYPT_FAILED(6002, "API Key 解密失败"),

    // 记忆 7xxx
    MEMORY_NOT_FOUND(7001, "记忆不存在"),

    // 群聊 8xxx
    GROUP_FULL(8001, "群聊成员已满"),
    NOT_GROUP_MEMBER(8002, "非群聊成员"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
