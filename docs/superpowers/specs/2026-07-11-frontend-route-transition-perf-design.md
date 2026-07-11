# 前端跳转与交互卡顿优化设计（方案 A，零构建变更）

> **目标：** 在不破坏任何原有功能、不改动构建配置与依赖的前提下，消除 Electron 客户端
> 路由跳转与页内交互的卡顿感。瓶颈诊断已定位为「装好的 Electron 客户端」中的
> `out-in` 过渡强制 0.43s 空转 + 无 keep-alive 重挂载重拉数据 + 过渡期间玻璃层重绘抢 GPU 帧。

**架构：** 全部改动落在运行时（`App.vue` / `main.js` / 全局 SCSS / 一个新 composable / 7 个列表页各加 `onActivated`）。
不引入依赖、不改 `vite.config.js`、不改 Element Plus 引入方式。

**技术栈：** Vue 3.5 · vue-router 4.4 · Pinia 2.2 · Element Plus 2.8 · Vitest 2.x · SCSS

---

## 1. 瓶颈定位（证据）

| 位置 | 现状 | 问题 |
|---|---|---|
| `App.vue:9` | `<transition name="page" mode="out-in">` | out-in 串行：旧页 0.15s 退完才进新页 0.28s = 强制 0.43s 空转 |
| `global.scss:348` | `pageEnter 0.28s cubic-bezier(0.4,0,0.2,1)` 带 `translateY(10px)→0` | transform 让含 `backdrop-filter` 的页面逐帧重栅格化模糊层 → 过渡本身掉帧 |
| `global.scss:348` | 缓动 `cubic-bezier(0.4,0,0.2,1)` | 违反 DESIGN.md / CLAUDE.md 规则 5（须 EaseOutQuint） |
| `App.vue:73` `viewKey` + 无 `keep-alive` | 每次切回列表页都 remount + `onMounted` 重拉云端数据 | 回页延迟 = 网络往返 |
| `global.scss:63` `ambientShift 20s` + `app-shell.scss:815/824` `atmosphere-float 9~11s` | 持续动画在过渡期间抢 GPU 合成预算 | 过渡那几帧被气氛层吃掉 |

## 2. 解决方案

### 2.1 路由过渡瘦身
- 保留 `out-in` 模式（不切同时交叉，避免两层堆叠布局抖动，最稳）。
- `pageLeave` 0.15s → 0.10s；`pageEnter` 0.28s → 0.18s。总空转 0.43s → 0.28s。
- 两段都只动 `opacity`，删除 `translateY`。纯 opacity 走合成层，不触发 backdrop-filter 重栅格化。
- 缓动统一 `cubic-bezier(0.23,1,0.32,1)`（EaseOutQuint），补合规债。
- `@media (prefers-reduced-motion: reduce)` 下两段 `animation: none`，即时切换。

### 2.2 keep-alive + stale-while-revalidate
- `App.vue:8` 在 `<transition>` 内包 `<keep-alive :include="cacheableNames">`。
- 只缓存 7 个重列表页：Home / Characters / CharacterSquare / Moments / Memory / Diary / Profile。
- 不缓存：Chat / CharacterChatDetail（按 id 挂载，缓存爆内存+状态错乱）、GroupChat（会话状态）、
  Settings / QqBridge / About（轻量/可弃表单）、Landing / Login / Register / Launcher / QuickChat。
- 每个缓存页加 `onActivated`，调它已有的 fetch 函数做后台重取：先显缓存内容，后台回来再替换。
- 不跨页做失效 wiring——SWR 保证数据最终一致，最稳，不会「漏一个失效点」变功能破坏。
- keep-alive 的 `:include` 按**组件 name**匹配。现状 7 个缓存页均为 `<script setup>` 且**无** `defineOptions({name})`（已 grep 确认），匹配会失效。**必须**给这 7 个页各加 `defineOptions({ name: 'HomePage' })` 等，与 include 名单逐一对应。
- `viewKey`（`App.vue:73`）不动；7 个缓存页的 key 均为 `route.path`（稳定），keep-alive 可正确恢复实例。

### 2.3 跳转期间暂停气氛动画
- 路由变化时给 `<html>` 加 class `ly-route-transitioning`，~220ms 后移除。
- CSS：`.ly-route-transitioning` 下把 `atmosphere-float` 与 `#app::before` 的 `ambientShift` 设 `animation-play-state: paused`。
- 过渡那 0.18s 让 GPU 帧预算给页面淡入，结束立即恢复。无视觉差异。

### 2.4 空闲预热相邻路由
- `main.js` 中 `app.mount` + `router.isReady()` 后，`requestIdleCallback` 把 6 个主应用页 chunk `import()` 一遍：
  Home / Characters / CharacterSquare / Moments / Memory / Settings。
- 让 Vite 把 chunk 解析+编译进内存，消除首次进页的 JS parse 抖动。
- 失败静默 catch，不影响启动。Launcher/QuickChat 表面跳过（轻量）。

## 3. 不破坏功能保证
- 过渡仍在（淡入淡出），仅更短+纯 opacity——感知是「更顺」，无行为变化。
- keep-alive 只盖 7 个列表页；详情/聊天/会话页完全不受影响。
- SWR 保证回页数据最终一致。
- 气氛动画暂停是瞬态（0.18s），结束即恢复。
- 预热是纯增量，不改运行时逻辑。
- 全程只用 DESIGN.md 令牌 + EaseOutQuint，合规。
- 不改 `vite.config.js` / `package.json` / Element Plus 引入方式。

## 4. 验证策略（TDD）
- 单测：`useKeepAlivePages` 返回正确 include 名单；`viewKey` 行为不变；空闲预热集合正确；
  路由切换时 `ly-route-transitioning` add/remove 时机正确。
- 组件测：`App.vue` 渲染了 `<keep-alive :include=...>` 且 transition name='page' mode='out-in' 不变。
- 行为测（`vitest.e2e.config.js`）：Home→Characters→Home，断言 Characters `onMounted` 只调一次、`onActivated` 第二次触发。

## 5. 改动面（逐文件，便于回滚）
- `frontend/src/App.vue` — 包 keep-alive + 过渡 class 切换
- `frontend/src/main.js` — 空闲预热块
- `frontend/src/composables/useKeepAlivePages.js` — 新增，返回名单
- `frontend/src/styles/global.scss` — 过渡 keyframes 瘦身 + paused 规则 + reduced-motion
- `frontend/src/styles/app-shell.scss` — 1 条 paused 规则
- 7 个列表页 — 各加 `onActivated` 调已有 fetch
