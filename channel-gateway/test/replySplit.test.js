import assert from 'node:assert/strict';
import test from 'node:test';

import { createQqBotHandler } from '../src/qqbot/handler.js';
import { splitReply } from '../src/qqbot/replySplit.js';

test('splitReply：按换行拆成两条', () => {
  assert.deepEqual(splitReply('先别较劲了。\n明天的事明天再说。', 2), [
    '先别较劲了。',
    '明天的事明天再说。',
  ]);
});

test('splitReply：括号里的换行不拆', () => {
  const text = '（把杯子推过去\n又缩回手）喝点水吧。';
  const parts = splitReply(text, 2);
  assert.equal(parts.length, 1);
  assert.match(parts[0], /缩回手/);
});

test('splitReply：单行长句超过 40 字按句子拆', () => {
  const text =
    '那就先别跟这股烦躁劲儿较劲了，越较劲它越缠人。喝口水，或者随便做点别的，几分钟就好——明天的事明天再说，总不能因为烦，今晚就不睡了。';
  const parts = splitReply(text, 2);
  assert.equal(parts.length, 2);
  assert.ok(parts.every((part) => part.length > 0));
  assert.match(parts[1], /明天的事明天再说/);
});

test('splitReply：超过上限时把尾巴并回最后一条', () => {
  const parts = splitReply('一。\n二。\n三。', 2);
  assert.equal(parts.length, 2);
  assert.equal(parts[0], '一。');
  assert.equal(parts[1], '二。 三。');
});

test('splitReply：空文本与纯空白', () => {
  assert.deepEqual(splitReply('', 2), []);
  assert.deepEqual(splitReply('   \n  ', 2), []);
});

test('handler：两条气泡按 msg_seq 1、2 发出，图片接 3', async () => {
  const calls = [];
  const api = {
    async sendC2C(openid, content, options) {
      calls.push({ openid, content, seq: options?.msgSeq });
    },
    async sendC2CImage(openid, url, options) {
      calls.push({ openid, image: url, seq: options?.msgSeq });
    },
    async sendGroup() {},
    async sendGroupImage() {},
  };
  const backend = {
    origin: 'http://127.0.0.1:8080',
    async turn() {
      return {
        reply: '先别较劲了。\n明天的事明天再说。',
        conversationId: 953,
        imageUrl: '/api/public/files/chat-images/x.png',
      };
    },
  };
  const handler = createQqBotHandler({
    api,
    backend,
    log: () => {},
    maxBubbles: 2,
    bubbleDelayMs: 0,
  });

  const result = await handler.handle({
    kind: 'c2c',
    openid: 'OPENID',
    msgId: 'MSG1',
    text: '唉，还是好烦',
  });

  assert.equal(result.bubbles, 2);
  assert.deepEqual(
    calls.map((call) => call.seq),
    [1, 2, 3],
  );
  assert.equal(calls[0].content, '先别较劲了。');
  assert.equal(calls[1].content, '明天的事明天再说。');
  assert.match(calls[2].image, /^http:\/\/127\.0\.0\.1:8080\/api\/public\/files\/chat-images\/x\.png$/);
});
