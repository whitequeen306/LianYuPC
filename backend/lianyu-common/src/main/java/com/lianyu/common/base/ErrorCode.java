package com.lianyu.common.base;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // 通用
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "请求有误，请检查后重试"),
    UNAUTHORIZED(401, "登录已过期，请重新登录"),
    FORBIDDEN(403, "暂无权限执行此操作"),
    CONTENT_POLICY_VIOLATION(403, "内容不符合平台规范"),
    NOT_FOUND(404, "找不到相关内容"),
    CONFLICT(409, "操作冲突，请刷新后重试"),
    INTERNAL_ERROR(500, "服务暂时不可用，请稍后再试"),

    // 用户 1xxx
    USERNAME_EXISTS(1001, "用户名已存在"),
    WRONG_PASSWORD(1002, "密码错误，请重试"),
    ACCOUNT_NOT_REGISTERED(1006, "该账号还未注册，请先注册试试"),
    USER_NOT_FOUND(1003, "用户不存在"),
    USER_DISABLED(1004, "账号已被禁用"),
    AUTH_RATE_LIMITED(1005, "尝试次数过多，请稍后再试"),

    // 角色 2xxx
    CHARACTER_NOT_FOUND(2001, "角色不存在"),
    CHARACTER_LIMIT(2002, "角色数量已达上限"),
    CHARACTER_ALREADY_ADDED(2003, "你已经添加过该角色了哦"),

    // 对话 3xxx
    CONVERSATION_NOT_FOUND(3001, "对话不存在"),
    MESSAGE_NOT_FOUND(3002, "消息不存在"),

    // AI 4xxx
    AI_PROVIDER_ERROR(4001, "对话服务暂时不可用，请稍后再试"),
    AI_TIMEOUT(4002, "回复超时，请稍后再试"),
    AI_RATE_LIMITED(4003, "请求太频繁，请稍后再试"),
    MODEL_NOT_FOUND(4004, "所选模型不可用，请换一个试试"),

    // 文件 5xxx
    FILE_TOO_LARGE(5001, "文件太大，请换一张小一点的"),
    FILE_TYPE_DENIED(5002, "不支持该文件类型"),
    UPLOAD_FAILED(5003, "上传失败，请稍后再试"),

    // 安全 6xxx
    API_KEY_ENCRYPT_FAILED(6001, "配置保存失败，请稍后再试"),
    API_KEY_DECRYPT_FAILED(6002, "配置读取失败，请稍后再试"),

    // 记忆 7xxx
    MEMORY_NOT_FOUND(7001, "记忆不存在"),

    // 群聊 8xxx
    GROUP_FULL(8001, "群聊成员已满"),
    NOT_GROUP_MEMBER(8002, "你不是该群聊成员"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
