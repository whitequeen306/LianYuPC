// QQ 消息里的「非文字」部分：表情标记与图片附件。
//
// 实测（2026-11-14，C2C）：
//   表情包 → content 变成 `<faceType=6,faceId="0",ext="eyJ0ZXh0IjoiIn0="/>` 这种标记，
//            模型看不懂就会瞎猜成某种情绪（我们真被猜成「叹气」过）。
//   图片   → content 为空，图片在 attachments 里（content_type=image/jpeg|image/gif + 限时 URL）。
//
// 所以这里做两件事：把标记翻成人话、把附件里的第一张图挑出来。

// faceType=6 实测是「表情包 / 大表情」，1 是普通小黄脸。ext 是 base64 的 JSON，里面可能有文字。
const FACE_RE = /<faceType=(\d+),faceId="([^"]*)"(?:,ext="([^"]*)")?\s*\/?>/g;
// 群聊 @ 与其它富文本残留，避免原样喂给模型。
const MENTION_RE = /<@!?\d+>/g;

// 小黄脸的官方语义（faceId → 名字）。只收我们确定的那批，宁缺勿错：
// 猜错情绪比不给更糟——这正是「叹气」那个 bug 的教训。
const FACE_NAMES = {
  0: '微笑', 1: '撇嘴', 2: '色', 3: '发呆', 4: '得意', 5: '流泪', 6: '害羞', 7: '闭嘴',
  8: '睡', 9: '大哭', 10: '尴尬', 11: '发怒', 12: '调皮', 13: '呲牙', 14: '惊讶', 15: '难过',
  16: '酷', 17: '冷汗', 18: '抓狂', 19: '吐', 20: '偷笑', 21: '可爱', 22: '白眼', 23: '傲慢',
  24: '饥饿', 25: '困', 26: '惊恐', 27: '流汗', 28: '憨笑', 30: '奋斗', 31: '咒骂', 32: '疑问',
  33: '嘘', 34: '晕', 35: '折磨', 36: '衰', 37: '骷髅', 38: '敲打', 39: '再见', 40: '擦汗',
  41: '抠鼻', 42: '鼓掌', 43: '糗大了', 44: '坏笑', 45: '左哼哼', 46: '右哼哼', 47: '哈欠',
  48: '鄙视', 49: '委屈', 50: '快哭了', 51: '阴险', 52: '亲亲', 53: '吓', 54: '可怜', 55: '菜刀',
  56: '西瓜', 57: '啤酒', 58: '篮球', 59: '乒乓', 60: '咖啡', 61: '饭', 62: '猪头', 63: '玫瑰',
  64: '凋谢', 65: '示爱', 66: '爱心', 67: '心碎', 68: '蛋糕', 69: '闪电', 70: '炸弹', 71: '刀',
  72: '足球', 73: '瓢虫', 74: '便便', 75: '月亮', 76: '太阳', 77: '礼物', 78: '拥抱', 79: '强',
  80: '弱', 81: '握手', 82: '胜利', 83: '抱拳', 84: '勾引', 85: '拳头', 86: '差劲', 87: '爱你',
  88: 'NO', 89: 'OK', 90: '爱情', 91: '飞吻', 92: '跳跳', 93: '发抖', 94: '怄火', 95: '转圈',
  96: '磕头', 97: '回头', 98: '跳绳', 99: '挥手', 100: '激动',
};

/** ext 是 base64(JSON)，实测 `{"text":""}`；有文字就带上，没有就算了。 */
function extText(ext) {
  if (!ext) return '';
  try {
    const parsed = JSON.parse(Buffer.from(ext, 'base64').toString('utf8'));
    const text = typeof parsed?.text === 'string' ? parsed.text.trim() : '';
    return text.slice(0, 12);
  } catch {
    return '';
  }
}

function faceLabel(faceType, faceId, ext) {
  if (String(faceType) === '6') {
    // 表情包只有编号，腾讯没给图；用户想表达的情绪我们确实看不到，只能如实说「发了个表情包」。
    const extra = extText(ext);
    return extra ? `[表情包:${extra}]` : '[表情包]';
  }
  const name = FACE_NAMES[String(faceId)];
  return name ? `[表情:${name}]` : '[表情]';
}

/** 把 QQ 的富文本标记换成模型看得懂的描述。 */
export function cleanText(raw) {
  return String(raw || '')
    .replace(FACE_RE, (_m, faceType, faceId, ext) => faceLabel(faceType, faceId, ext))
    .replace(MENTION_RE, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 附件里的第一张图（QQ 图片走这里；表情包没有附件）。 */
export function pickImage(event) {
  const attachments = event?.raw?.attachments;
  if (!Array.isArray(attachments)) return null;
  for (const item of attachments) {
    const contentType = String(item?.content_type || '').toLowerCase();
    const url = typeof item?.url === 'string' ? item.url : '';
    if (contentType.startsWith('image/') && /^https?:\/\//i.test(url)) {
      return { url, contentType };
    }
  }
  return null;
}

/** 只有图没有字时给模型一个占位，否则回合会因为「内容为空」被挡掉。 */
export const IMAGE_PLACEHOLDER = '[图片]';
