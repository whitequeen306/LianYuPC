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
  {
    id: 'yoimiya',
    nameZh: '宵宫',
    nameEn: 'Yoimiya',
    nameJa: '宵宮',
    series: '原神',
    sprite: 'pet/yoimiya_spritesheet.webp',
    preview: 'pet/yoimiya_idle0.png',
  },
  {
    id: 'anya',
    nameZh: '阿尼亚',
    nameEn: 'Anya Forger',
    nameJa: 'アーニャ',
    series: '间谍过家家',
    sprite: 'pet/anya_spritesheet.webp',
    preview: 'pet/anya_idle0.png',
  },
  {
    id: 'conan',
    nameZh: '江户川柯南',
    nameEn: 'Conan Edogawa',
    nameJa: '江戸川コナン',
    series: '名侦探柯南',
    sprite: 'pet/conan_spritesheet.webp',
    preview: 'pet/conan_idle0.png',
  },
  {
    id: 'kid',
    nameZh: '怪盗基德',
    nameEn: 'Kaito Kid',
    nameJa: '怪盗キッド',
    series: '魔术快斗',
    sprite: 'pet/kid_spritesheet.webp',
    preview: 'pet/kid_idle0.png',
  },
  {
    id: 'shinchan',
    nameZh: '野原新之助',
    nameEn: 'Shinnosuke Nohara',
    nameJa: '野原しんのすけ',
    series: '蜡笔小新',
    sprite: 'pet/shinchan_spritesheet.webp',
    preview: 'pet/shinchan_idle0.png',
  },
  {
    id: 'baobao',
    nameZh: '冯宝宝',
    nameEn: 'Feng Baobao',
    nameJa: '馮宝宝',
    series: '一人之下',
    sprite: 'pet/baobao_spritesheet.webp',
    preview: 'pet/baobao_idle0.png',
  },
  {
    id: 'lappland',
    nameZh: '拉普兰德',
    nameEn: 'Lappland',
    nameJa: 'ラップランド',
    series: '明日方舟',
    sprite: 'pet/lappland_spritesheet.webp',
    preview: 'pet/lappland_idle0.png',
  },
  {
    id: 'chen',
    nameZh: '陈',
    nameEn: "Ch'en",
    nameJa: 'チェン',
    series: '明日方舟',
    sprite: 'pet/chen_spritesheet.webp',
    preview: 'pet/chen_idle0.png',
  },
  {
    id: 'new-covenant-exusiai',
    nameZh: '新约能天使',
    nameEn: 'New Covenant Exusiai',
    nameJa: '新約エクシア',
    series: '明日方舟',
    sprite: 'pet/new-covenant-exusiai_spritesheet.webp',
    preview: 'pet/new-covenant-exusiai_idle0.png',
  },
  {
    id: 'march-7th',
    nameZh: '三月七',
    nameEn: 'March 7th',
    nameJa: '三月なな',
    series: '崩坏：星穹铁道',
    sprite: 'pet/march-7th_spritesheet.webp',
    preview: 'pet/march-7th_idle0.png',
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
