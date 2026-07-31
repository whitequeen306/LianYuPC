/** 保留别名：服务端禁止用户 vault 使用此名；聊天 UI 不再提供该选项 */
export const PLATFORM_PROVIDER = 'platform'

/** @deprecated 平台内置聊天模型已下线；勿再作为默认发送 provider */
export const PLATFORM_MODEL = ''

/** Platform default VL model when chat/QQ visionModel is empty */
export const PLATFORM_VISION_MODEL = 'qwen3-vl-plus'

/** @deprecated 聊天不再提供「平台默认」选项 */
export const PLATFORM_PROVIDER_LABEL = '需配置自有模型'

export const PLATFORM_VISION_MODEL_LABEL = '平台默认识图 (qwen3-vl-plus)'

/** Suggested VL model ids for selects (allow-create still enabled) */
export const VISION_MODEL_SUGGESTIONS = [
  { id: '', name: PLATFORM_VISION_MODEL_LABEL },
  { id: 'qwen3-vl-plus', name: 'qwen3-vl-plus' },
  { id: 'qwen3-vl-flash', name: 'qwen3-vl-flash' },
  { id: 'qwen-vl-plus', name: 'qwen-vl-plus' },
  { id: 'qwen-vl-max', name: 'qwen-vl-max' },
]
