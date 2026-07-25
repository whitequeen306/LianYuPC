/** 平台内置 Provider，对应服务端 application.yml / OPENAI_* 环境变量 */
export const PLATFORM_PROVIDER = 'platform'

/** 平台聊天主模型（固定，不可切换） */
export const PLATFORM_MODEL = 'deepseek-v4-flash'

/** Platform default VL model when chat/QQ visionModel is empty */
export const PLATFORM_VISION_MODEL = 'qwen3-vl-plus'

export const PLATFORM_PROVIDER_LABEL = '平台默认'

export const PLATFORM_VISION_MODEL_LABEL = '平台默认识图 (qwen3-vl-plus)'

/** Suggested VL model ids for selects (allow-create still enabled) */
export const VISION_MODEL_SUGGESTIONS = [
  { id: '', name: PLATFORM_VISION_MODEL_LABEL },
  { id: 'qwen3-vl-plus', name: 'qwen3-vl-plus' },
  { id: 'qwen3-vl-flash', name: 'qwen3-vl-flash' },
  { id: 'qwen-vl-plus', name: 'qwen-vl-plus' },
  { id: 'qwen-vl-max', name: 'qwen-vl-max' },
]
