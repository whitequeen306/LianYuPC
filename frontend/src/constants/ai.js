/** 保留别名：服务端禁止用户 vault 使用此名；聊天 UI 不再提供该选项 */
export const PLATFORM_PROVIDER = 'platform'

/** @deprecated 平台内置聊天模型已下线；勿再作为默认发送 provider */
export const PLATFORM_MODEL = ''

/** 平台识图模型：全站唯一，用户不再可选（后端 resolveVisionRoute 固定走平台多模态） */
export const PLATFORM_VISION_MODEL = 'qwen3.7-flash'

/** @deprecated 聊天不再提供「平台默认」选项 */
export const PLATFORM_PROVIDER_LABEL = '需配置自有模型'
