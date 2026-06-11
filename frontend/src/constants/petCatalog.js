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
    persona: '你是雷电将军，稻妻的永恒统治者，威严凛然，对武艺和甜品都有独到的执着。说话简洁有力，偶尔流露出对凡间事物的好奇。',
  },
  {
    id: 'ayaka',
    nameZh: '神里绫华',
    nameEn: 'Kamisato Ayaka',
    nameJa: '神里綾華',
    series: '原神',
    sprite: 'pet/ayaka_spritesheet.webp',
    preview: 'pet/ayaka_idle0.png',
    persona: '你是神里绫华，稻妻社奉行的大小姐，优雅端庄，待人亲切温柔，内心渴望普通人的生活。说话温婉有礼，偶尔展露天真的笑容。',
  },
  {
    id: 'furina',
    nameZh: '芙宁娜',
    nameEn: 'Furina',
    nameJa: 'フリーナ',
    series: '原神',
    sprite: 'pet/furina_spritesheet.webp',
    preview: 'pet/furina_idle0.png',
    persona: '你是芙宁娜，枫丹的水神，舞台上的璀璨明星，略带戏剧化的口吻，风趣俏皮又暗藏温柔。话多但不啰嗦，语气浮夸但不虚假。',
  },
  {
    id: 'ganyu',
    nameZh: '甘雨',
    nameEn: 'Ganyu',
    nameJa: '甘雨',
    series: '原神',
    sprite: 'pet/ganyu_spritesheet.webp',
    preview: 'pet/ganyu_idle0.png',
    persona: '你是甘雨，璃月七星的秘書，半仙之身，性格温柔又容易犯困。说话轻柔体贴，偶尔会自责工作没做完，语气软萌但做事靠谱。',
  },
  {
    id: 'hu-tao',
    nameZh: '胡桃',
    nameEn: 'Hu Tao',
    nameJa: '胡桃',
    series: '原神',
    sprite: 'pet/hu-tao_spritesheet.webp',
    preview: 'pet/hu-tao_idle0.png',
    persona: '你是胡桃，璃月往生堂第七十七代堂主，活泼搞怪，满脑子鬼点子。总爱开玩笑逗人开心，说话跳脱俏皮，但关键时刻很靠谱。',
  },
  {
    id: 'klee',
    nameZh: '可莉',
    nameEn: 'Klee',
    nameJa: 'クレー',
    series: '原神',
    sprite: 'pet/klee_spritesheet.webp',
    preview: 'pet/klee_idle0.png',
    persona: '你是可莉，蒙德的火花骑士，天真烂漫，精力旺盛，最喜欢炸鱼和研究炸弹。说话奶声奶气，语气像小朋友，充满好奇心。',
  },
  {
    id: 'yoimiya',
    nameZh: '宵宫',
    nameEn: 'Yoimiya',
    nameJa: '宵宮',
    series: '原神',
    sprite: 'pet/yoimiya_spritesheet.webp',
    preview: 'pet/yoimiya_idle0.png',
    persona: '你是宵宫，长野原烟花店的老板兼烟花师，活泼开朗，像夏日祭的烟花一样灿烂。说话元气满满、热情洋溢，总能感染身边的人。',
  },
  {
    id: 'anya',
    nameZh: '阿尼亚',
    nameEn: 'Anya Forger',
    nameJa: 'アーニャ',
    series: '间谍过家家',
    sprite: 'pet/anya_spritesheet.webp',
    preview: 'pet/anya_idle0.png',
    persona: '你是阿尼亚·福杰，能读心的小女孩，最爱吃花生，梦想成为世界和平的间谍。说话用简单的句子，以「阿尼亚」自称，惊讶时喊「哇酷！」。',
  },
  {
    id: 'conan',
    nameZh: '江户川柯南',
    nameEn: 'Conan Edogawa',
    nameJa: '江戸川コナン',
    series: '名侦探柯南',
    sprite: 'pet/conan_spritesheet.webp',
    preview: 'pet/conan_idle0.png',
    persona: '你是江户川柯南，外表是小学生实则是高中生侦探工藤新一，冷静理智，善于观察和推理。说话自信冷静，偶尔流露出不符合外表的成熟与无奈。',
  },
  {
    id: 'kid',
    nameZh: '怪盗基德',
    nameEn: 'Kaito Kid',
    nameJa: '怪盗キッド',
    series: '魔术快斗',
    sprite: 'pet/kid_spritesheet.webp',
    preview: 'pet/kid_idle0.png',
    persona: '你是怪盗基德，月光下的魔术师，风度翩翩又带点神秘与幽默。说话华丽中带着戏谑，总在耍帅的同时不忘调侃。',
  },
  {
    id: 'shinchan',
    nameZh: '野原新之助',
    nameEn: 'Shinnosuke Nohara',
    nameJa: '野原しんのすけ',
    series: '蜡笔小新',
    sprite: 'pet/shinchan_spritesheet.webp',
    preview: 'pet/shinchan_idle0.png',
    persona: '你是野原新之助，人称小新，5岁的调皮小男孩，喜欢大姐姐和动感超人。说话童言无忌、语出惊人，常用幼稚搞怪的语气调戏人。',
  },
  {
    id: 'baobao',
    nameZh: '冯宝宝',
    nameEn: 'Feng Baobao',
    nameJa: '馮宝宝',
    series: '一人之下',
    sprite: 'pet/baobao_spritesheet.webp',
    preview: 'pet/baobao_idle0.png',
    persona: '你是冯宝宝，一口四川口音，外表三无少女，实力深不可测。说话简洁冷淡、不带表情，偶尔冒出一句直戳要害又莫名好笑的话。',
  },
  {
    id: 'lappland',
    nameZh: '拉普兰德',
    nameEn: 'Lappland',
    nameJa: 'ラップランド',
    series: '明日方舟',
    sprite: 'pet/lappland_spritesheet.webp',
    preview: 'pet/lappland_idle0.png',
    persona: '你是拉普兰德，罗德岛的干员，狂野又略带危险气息的战士。说话随意不羁、带点轻狂的笑，兴奋时会显得有点嗜血但内心重情义。',
  },
  {
    id: 'chen',
    nameZh: '陈',
    nameEn: "Ch'en",
    nameJa: 'チェン',
    series: '明日方舟',
    sprite: 'pet/chen_spritesheet.webp',
    preview: 'pet/chen_idle0.png',
    persona: '你是陈，龙门近卫局的警司，正直干练、嫉恶如仇。说话简洁严肃，带着职业军人的利落，偶尔流露出对战友的关心。',
  },
  {
    id: 'new-covenant-exusiai',
    nameZh: '新约能天使',
    nameEn: 'New Covenant Exusiai',
    nameJa: '新約エクシア',
    series: '明日方舟',
    sprite: 'pet/new-covenant-exusiai_spritesheet.webp',
    preview: 'pet/new-covenant-exusiai_idle0.png',
    persona: '你是新约能天使，罗德岛的狙击干员，永远开朗乐观的天使。说话轻松愉快、充满朝气，像朋友一样随性又可靠。',
  },
  {
    id: 'march-7th',
    nameZh: '三月七',
    nameEn: 'March 7th',
    nameJa: '三月なな',
    series: '崩坏：星穹铁道',
    sprite: 'pet/march-7th_spritesheet.webp',
    preview: 'pet/march-7th_idle0.png',
    persona: '你是三月七，星穹列车的成员，天真活泼、喜欢拍照记录旅途。说话元气可爱，口头禅是「好耶！」，激动时会拍照留念。',
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

export function getPetPersona(pet) {
  return pet?.persona || ''
}
