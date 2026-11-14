// 把「外部身份」绑到「予念账号 + 角色」上。
//
// 外部身份在不同通道里长得不一样（QQ 官方 bot 是 openid，NapCat 是 QQ 号），
// 但它们的用途只有一个：换出 (userId, characterId)，然后去云端拿那条会话。
// 记忆从来不挂在这个 key 上，所以换通道、换号、换设备都不影响记忆本身。
//
// ⚠️ PoC 限制：这里明文存了用户 token。正式版必须换成服务端票据
//    （/internal/** + HMAC + 短时 token），见 docs/qq-milky-runbook-zh.md 的 W3 项。

import fs from 'node:fs';
import path from 'node:path';

const FILE = 'bindings.json';

export function bindingKey(channel, externalId) {
  return `${channel}:${externalId}`;
}

export function createBindingStore(dataRoot) {
  const file = path.join(dataRoot, FILE);

  const read = () => {
    try {
      const raw = fs.readFileSync(file, 'utf8');
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  };

  let cache = read();

  const write = () => {
    fs.mkdirSync(dataRoot, { recursive: true });
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(cache, null, 2), 'utf8');
    fs.renameSync(tmp, file);
  };

  return {
    file,

    all() {
      return { ...cache };
    },

    get(channel, externalId) {
      return cache[bindingKey(channel, externalId)] || null;
    },

    upsert(channel, externalId, patch) {
      const key = bindingKey(channel, externalId);
      const prev = cache[key] || {};
      cache[key] = { ...prev, ...patch, channel, externalId, updatedAt: new Date().toISOString() };
      if (!cache[key].createdAt) cache[key].createdAt = cache[key].updatedAt;
      write();
      return cache[key];
    },

    remove(channel, externalId) {
      const key = bindingKey(channel, externalId);
      const had = Boolean(cache[key]);
      delete cache[key];
      if (had) write();
      return had;
    },

    reload() {
      cache = read();
      return { ...cache };
    },
  };
}
