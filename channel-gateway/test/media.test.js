// 图片与表情包：QQ 给的东西不是纯文本，这里锁住「翻译规则」和「转交给后端的方式」。
import assert from 'node:assert/strict';
import test from 'node:test';
import { IMAGE_PLACEHOLDER, cleanText, pickImage } from '../src/qqbot/media.js';
import { createQqBotHandler } from '../src/qqbot/handler.js';
import { createQqBotApi } from '../src/qqbot/api.js';

// 实测样本：用户在 C2C 里发的一个大表情（表情包）。
const FACE_PACK = '<faceType=6,faceId="0",ext="eyJ0ZXh0IjoiIn0="/>';
const FACE_SMALL = '<faceType=1,faceId="22"/>';

const IMAGE_ATTACHMENT = {
  content: '',
  content_type: 'image/jpeg',
  filename: '18023EE3A7CD09B23C673C9BB62FFBB9.jpg',
  height: 1144,
  size: 104385,
  url: 'https://multimedia.nt.qq.com.cn/download?appid=1406&fileid=abc&rkey=xyz',
  width: 885,
};

test('cleanText：小黄脸翻成具体名字，不认识的仍然只是 [表情]', () => {
  assert.equal(cleanText(FACE_PACK), '[表情包]');
  assert.equal(cleanText(FACE_SMALL), '[表情:白眼]');
  assert.equal(cleanText('<faceType=1,faceId="99999"/>'), '[表情]');
  assert.equal(cleanText(`你好${FACE_PACK}呀`), '你好[表情包]呀');
});

test('cleanText：表情包 ext 里有文字就带上（腾讯偶尔给 caption）', () => {
  const withText = `<faceType=6,faceId="1",ext="${Buffer.from(JSON.stringify({ text: '在忙' })).toString('base64')}"/>`;
  assert.equal(cleanText(withText), '[表情包:在忙]');
  const broken = '<faceType=6,faceId="1",ext="not-base64!!"/>';
  assert.equal(cleanText(broken), '[表情包]');
});

test('cleanText：群聊 @ 与多余空白不留进模型上下文', () => {
  assert.equal(cleanText('<@!123456> 在吗   你在'), '在吗 你在');
  assert.equal(cleanText(''), '');
  assert.equal(cleanText(null), '');
});

test('pickImage：取附件里第一张图（表情包也走这里）', () => {
  const event = { raw: { attachments: [IMAGE_ATTACHMENT] } };
  assert.equal(pickImage(event).contentType, 'image/jpeg');
  assert.match(pickImage(event).url, /^https:\/\/multimedia\.nt\.qq\.com\.cn\//);

  // 非图片附件（语音/文件）不当作图片
  assert.equal(pickImage({ raw: { attachments: [{ content_type: 'voice/silk', url: 'https://x' }] } }), null);
  // 没有附件、或附件不是 http 地址，都不返回
  assert.equal(pickImage({ raw: {} }), null);
  assert.equal(pickImage({ raw: { attachments: [{ content_type: 'image/png', url: 'file:///a.png' }] } }), null);
});

test('handler：只有图片没有文字时也要跑回合，并把图片字节交给后端', async () => {
  const sent = [];
  const turns = [];
  const handler = createQqBotHandler({
    api: {
      sendC2C: async (openid, content, opts) => sent.push({ openid, content, opts }),
      sendGroup: async () => {},
      sendC2CImage: async () => {},
      sendGroupImage: async () => {},
    },
    backend: {
      turn: async (payload) => {
        turns.push(payload);
        return { reply: '这张图我看到了', conversationId: 953 };
      },
    },
    fetchImage: async () => ({ base64: 'AAAA', contentType: 'image/jpeg' }),
  });

  const result = await handler.handle({
    kind: 'c2c',
    openid: 'u1',
    msgId: 'm1',
    text: '',
    raw: { attachments: [IMAGE_ATTACHMENT] },
  });

  assert.equal(result.replied, true);
  assert.equal(turns.length, 1);
  assert.equal(turns[0].text, IMAGE_PLACEHOLDER);
  assert.equal(turns[0].imageBase64, 'AAAA');
  assert.equal(turns[0].imageContentType, 'image/jpeg');
  assert.equal(sent[0].content, '这张图我看到了');
});

test('handler：图片下载失败不挡这一轮，按纯文本继续', async () => {
  const turns = [];
  const handler = createQqBotHandler({
    api: { sendC2C: async () => {}, sendGroup: async () => {}, sendC2CImage: async () => {}, sendGroupImage: async () => {} },
    backend: {
      turn: async (payload) => {
        turns.push(payload);
        return { reply: '嗯', conversationId: 1 };
      },
    },
    fetchImage: async () => {
      throw new Error('http_403');
    },
  });

  await handler.handle({
    kind: 'c2c',
    openid: 'u1',
    msgId: 'm2',
    text: '',
    raw: { attachments: [IMAGE_ATTACHMENT] },
  });

  assert.equal(turns[0].imageBase64, undefined);
  assert.equal(turns[0].text, IMAGE_PLACEHOLDER);
});

test('handler：回复里带图就再发一条富媒体（msg_seq=2），失败不影响文字', async () => {
  const images = [];
  const handler = createQqBotHandler({
    api: {
      sendC2C: async () => {},
      sendGroup: async () => {},
      sendC2CImage: async (openid, url, opts) => images.push({ openid, url, opts }),
      sendGroupImage: async () => {},
    },
    backend: { turn: async () => ({ reply: '给你看', conversationId: 1, imageUrl: 'https://cdn/x.png' }) },
  });

  await handler.handle({ kind: 'c2c', openid: 'u1', msgId: 'm3', text: '发张图' });
  assert.equal(images.length, 1);
  assert.equal(images[0].url, 'https://cdn/x.png');
  assert.equal(images[0].opts.msgSeq, 2);

  const broken = createQqBotHandler({
    api: {
      sendC2C: async () => {},
      sendGroup: async () => {},
      sendC2CImage: async () => {
        throw new Error('image_upload_no_file_info');
      },
      sendGroupImage: async () => {},
    },
    backend: { turn: async () => ({ reply: '给你看', conversationId: 1, imageUrl: 'https://cdn/x.png' }) },
  });
  const result = await broken.handle({ kind: 'c2c', openid: 'u1', msgId: 'm4', text: '发张图' });
  assert.equal(result.replied, true);
});

test('api.sendC2CImage：先换 file_info 再发 msg_type=7', async () => {
  const calls = [];
  const api = createQqBotApi({
    appId: '1',
    clientSecret: 's',
    fetchImpl: async (url, init) => {
      if (String(url).includes('getAppAccessToken')) {
        return { ok: true, status: 200, json: async () => ({ access_token: 'T', expires_in: 7200 }), text: async () => '{}' };
      }
      calls.push({ url, body: JSON.parse(init.body) });
      if (String(url).includes('/files')) return { ok: true, status: 200, json: async () => ({ file_info: 'FI' }), text: async () => '{"file_info":"FI"}' };
      return { ok: true, status: 200, json: async () => ({ id: 'm1' }), text: async () => '{"id":"m1"}' };
    },
  });

  await api.sendC2CImage('u1', 'https://cdn/x.png', { msgId: 'm0' });
  const upload = calls.find((c) => c.url.includes('/v2/users/u1/files'));
  const message = calls.find((c) => c.url.includes('/v2/users/u1/messages'));
  assert.deepEqual(upload.body, { file_type: 1, url: 'https://cdn/x.png', srv_send_msg: false });
  assert.equal(message.body.msg_type, 7);
  assert.deepEqual(message.body.media, { file_info: 'FI' });
  assert.equal(message.body.msg_id, 'm0');
});
