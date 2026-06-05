import { resolveStaticAsset } from '@/utils/media.js'

/** 默认桌宠 */
export const DEFAULT_PET_ID = 'raiden'

/** Codex 8×9 标准 atlas，本地已打包的桌宠 */
export const PET_CATALOG = [
  {
    id: 'raiden',
    nameZh: '雷电将军',
    nameEn: 'Raiden Shogun',
    nameJa: '雷電将軍',
    series: '原神',
    sprite: 'pet/raiden_spritesheet.webp',
    preview: 'pet/raiden_idle0.png',
  },
  {
    id: 'ayaka',
    nameZh: '神里绫华',
    nameEn: 'Kamisato Ayaka',
    nameJa: '神里綾華',
    series: '原神',
    sprite: 'pet/ayaka_spritesheet.webp',
    preview: 'pet/ayaka_idle0.png',
  },
  {
    id: 'furina',
    nameZh: '芙宁娜',
    nameEn: 'Furina',
    nameJa: 'フリーナ',
    series: '原神',
    sprite: 'pet/furina_spritesheet.webp',
    preview: 'pet/furina_idle0.png',
  },
  {
    id: 'ganyu',
    nameZh: '甘雨',
    nameEn: 'Ganyu',
    nameJa: '甘雨',
    series: '原神',
    sprite: 'pet/ganyu_spritesheet.webp',
    preview: 'pet/ganyu_idle0.png',
  },
  {
    id: 'hu-tao',
    nameZh: '胡桃',
    nameEn: 'Hu Tao',
    nameJa: '胡桃',
    series: '原神',
    sprite: 'pet/hu-tao_spritesheet.webp',
    preview: 'pet/hu-tao_idle0.png',
  },
  {
    id: 'klee',
    nameZh: '可莉',
    nameEn: 'Klee',
    nameJa: 'クレー',
    series: '原神',
    sprite: 'pet/klee_spritesheet.webp',
    preview: 'pet/klee_idle0.png',
  },
]

export function isValidPetId(id) {
  return PET_CATALOG.some(p => p.id === id)
}

export function getPetById(id) {
  const found = PET_CATALOG.find(p => p.id === id)
  return found || PET_CATALOG.find(p => p.id === DEFAULT_PET_ID)
}

export function getPetSpriteUrl(pet) {
  return resolveStaticAsset(pet.sprite)
}

export function getPetPreviewUrl(pet) {
  return resolveStaticAsset(pet.preview)
}
