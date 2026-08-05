package com.lianyu.common.constant;

public final class AiConstants {

  /**
   * 已退役的平台 Provider 别名。平台 DEFAULT 池已拆除，所有对话与内部逻辑
   * （记忆抽取 / 会话摘要 / 群聊@裁决等）均使用用户自有模型；此常量仅作为保留名
   * 用于入参校验（拒绝用户创建/使用 platform 别名）。
   */
  public static final String PLATFORM_PROVIDER = "platform";

  private AiConstants() {}
}
