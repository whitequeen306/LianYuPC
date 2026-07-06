# About 页开发者区块改造 + 真机更新测试

## 背景

关于页面底部「开发者」区块需要：加头像、改名、改鸣谢文案。同时借本次前端改动发
v0.2.260，让装机版 v0.2.259 走一次真实的自动更新流程。

## 需求

1. 「青思雨」改名为「白之女王」，GitHub 链接 `whitequeen306` 不变
2. 白之女王与 Clove 各加一张圆形头像
3. 「其它鸣谢」正文由「恋语安卓端全体开发团队」改为
   「恋语安卓端全体开发团队以及各位用户」
4. 发版 v0.2.260 供 v0.2.259 真机更新测试

## 设计决策

### 头像存储：静态资源，不用 MinIO/MySQL

开发者名单几乎静态（名字、角色、链接都硬编码在 Vue 组件里，改名照样要重打
Electron 包）。对一段一年都不一定变一次的鸣谢名单，MinIO+MySQL 是过度设计。
结论：头像放 `frontend/public/devs/`，Vue 直接引用 `/devs/xxx.jpg`。

### 文件命名

源文件 `白之女王.jpg` / `Clove.jpg` → 目标 `white-queen.jpg` / `clove.jpg`。
用 ASCII 文件名避免 URL 编码问题，与项目现有 `frontend/public/landing/` 命名
风格一致。

## 实现

### 资源

- `avatar/白之女王.jpg` → `frontend/public/devs/white-queen.jpg`
- `avatar/Clove.jpg` → `frontend/public/devs/clove.jpg`

### AboutPage.vue 改动

- 每个 `dev-item`（`<a>`）左侧加 `<img class="dev-avatar">`
- `.dev-item` 由 `justify-content: space-between` 改为 `gap: $space-3`，
  链接图标用 `margin-left: auto` 推到最右（现在有 3 个子元素）
- 头像 CSS：40×40px，`border-radius: $radius-full`（DESIGN.md 规定圆形头像
  用 full=9999px），`object-fit: cover`，`flex-shrink: 0`
- 鸣谢项（`<p class="dev-item dev-thanks">`）只改文案，不加头像

### 布局示意

```
[头像] 白之女王                         [链接图标]
[头像] Clove.                           [链接图标]
       恋语安卓端全体开发团队以及各位用户
```

## 发版

- 当前装机 v0.2.259 → 发 v0.2.260
- 流程：`git commit` + `push origin main` → `cd frontend &&
  npm run electron:release`（自动 bump 版本、打包、传 GitHub Releases）
- 发完后装机版点「检查更新」应检测到 v0.2.260 并下载安装

## 不做的事

- 不动后端/MinIO/MySQL
- 不改 GitHub 链接、不改彩蛋逻辑、不碰其它区块
