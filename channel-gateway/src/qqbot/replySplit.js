// 把一段回复拆成 1~N 个聊天气泡。
//
// 为什么要有这个：模型一次吐一整段（三五行、一堆逗号），一次性甩给用户像贴公告。
// 手机 QQ 里的真人是一句一句发的，所以这里按「换行 → 句子」拆开，逐条发出去。
//
// 规矩和 Java 侧 AssistantReplySplitter 保持一致（App 里看到的就是分条存储），
// 这里只实现 QQ 通道需要的那部分：拆行、超长再拆句、封顶后把尾巴并回最后一条。
// 括号里的换行不切（动作/心理活动被劈成两半最难读）。

const SENTENCE_SPLIT_MIN_CHARS = 40;
const CJK_BOUNDARY = /(?<=[。！？…])(?=[^。！？…\s])/u;
const EN_BOUNDARY = /(?<=[.!?])\s+/;

const OPEN_BRACKETS = '（(【[';
const CLOSE_BRACKETS = '）)】]';

/** 按换行拆，但括号内部不拆。 */
function splitLinesOutsideBrackets(text) {
  const parts = [];
  let current = '';
  let depth = 0;
  for (const ch of text) {
    if (OPEN_BRACKETS.includes(ch)) depth += 1;
    if (CLOSE_BRACKETS.includes(ch)) depth = Math.max(0, depth - 1);
    if (ch === '\n' && depth === 0) {
      if (current.trim()) parts.push(current.trim());
      current = '';
      continue;
    }
    current += ch;
  }
  if (current.trim()) parts.push(current.trim());
  return parts;
}

function splitBySentence(text) {
  const byCjk = text.split(CJK_BOUNDARY).filter(Boolean);
  if (byCjk.length > 1) return byCjk;
  return text.split(EN_BOUNDARY).filter(Boolean);
}

/**
 * @param {string} text 模型回复原文
 * @param {number} maxParts 最多几条气泡（QQ 单聊每条用户消息最多回 4 条，含图片）
 */
export function splitReply(text, maxParts = 2) {
  const raw = String(text ?? '')
    .replace(/\r\n/g, '\n')
    .trim();
  if (!raw) return [];

  let pieces = splitLinesOutsideBrackets(raw);
  if (pieces.length === 0) pieces = [raw];

  if (pieces.length === 1 && pieces[0].length >= SENTENCE_SPLIT_MIN_CHARS) {
    const sentences = splitBySentence(pieces[0]);
    if (sentences.length > 1) pieces = sentences;
  }

  const limit = Math.max(1, Math.floor(Number(maxParts) || 1));
  if (pieces.length > limit) {
    const head = pieces.slice(0, limit - 1);
    const tail = pieces.slice(limit - 1).join(' ').trim();
    pieces = [...head, tail];
  }

  return pieces.map((piece) => piece.trim()).filter(Boolean);
}
