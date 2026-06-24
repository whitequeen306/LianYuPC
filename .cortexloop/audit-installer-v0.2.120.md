# Installer Security Audit — v0.2.120

- **Installer**: `C:\Users\hp\Desktop\LianYu-PC\frontend\release\v0.2.120\LianYu Setup 0.2.120.exe`
- **Generated**: 2026-06-24 05:02 UTC
- **Audit dir**: `C:\Users\hp\Desktop\LianYu-PC\release_audit`

## 1. Unpack tree

### level1 (NSIS)
```
  $PLUGINSDIR\app-64.7z (158,390,541 B)
  $PLUGINSDIR\modern-wizard.bmp (154,544 B)
  $PLUGINSDIR\nsDialogs.dll (9,728 B)
  $PLUGINSDIR\nsExec.dll (6,656 B)
  $PLUGINSDIR\nsis7z.dll (434,176 B)
  $PLUGINSDIR\StdUtils.dll (102,400 B)
  $PLUGINSDIR\System.dll (12,288 B)
  $PLUGINSDIR\UAC.dll (14,848 B)
  $PLUGINSDIR\WinShell.dll (3,072 B)
  $R0\Uninstall LianYu.exe (368,887 B)
  uninstallerIcon.ico (211,635 B)
```

### level2 (app-64.7z)
```
  chrome_100_percent.pak (119,889 B)
  chrome_200_percent.pak (197,073 B)
  d3dcompiler_47.dll (4,741,488 B)
  dxcompiler.dll (25,669,120 B)
  dxil.dll (1,509,760 B)
  ffmpeg.dll (3,057,152 B)
  icudtl.dat (10,876,560 B)
  LianYu.exe (224,125,440 B)
  libEGL.dll (478,208 B)
  libGLESv2.dll (8,003,584 B)
  LICENSE.electron.txt (1,096 B)
  LICENSES.chromium.html (20,367,095 B)
  locales\af.pak (620,482 B)
  locales\am.pak (1,006,731 B)
  locales\ar.pak (1,109,627 B)
  locales\bg.pak (1,150,764 B)
  locales\bn.pak (1,479,936 B)
  locales\ca.pak (699,584 B)
  locales\cs.pak (726,069 B)
  locales\da.pak (652,598 B)
  locales\de.pak (700,799 B)
  locales\el.pak (1,265,039 B)
  locales\en-GB.pak (565,496 B)
  locales\en-US.pak (571,518 B)
  locales\es-419.pak (690,699 B)
  locales\es.pak (686,799 B)
  locales\et.pak (627,098 B)
  locales\fa.pak (1,038,151 B)
  locales\fi.pak (638,637 B)
  locales\fil.pak (723,756 B)
  locales\fr.pak (746,468 B)
  locales\gu.pak (1,462,607 B)
  locales\he.pak (908,731 B)
  locales\hi.pak (1,549,657 B)
  locales\hr.pak (698,898 B)
  locales\hu.pak (747,469 B)
  locales\id.pak (618,246 B)
  locales\it.pak (675,913 B)
  locales\ja.pak (824,127 B)
  locales\kn.pak (1,681,221 B)
  locales\ko.pak (701,212 B)
  locales\lt.pak (759,941 B)
  locales\lv.pak (757,295 B)
  locales\ml.pak (1,728,736 B)
  locales\mr.pak (1,431,694 B)
  locales\ms.pak (651,309 B)
  locales\nb.pak (623,206 B)
  locales\nl.pak (649,223 B)
  locales\pl.pak (725,422 B)
  locales\pt-BR.pak (680,424 B)
  locales\pt-PT.pak (684,180 B)
  locales\ro.pak (710,046 B)
  locales\ru.pak (1,177,833 B)
  locales\sk.pak (738,142 B)
  locales\sl.pak (706,747 B)
  locales\sr.pak (1,088,532 B)
  locales\sv.pak (630,691 B)
  locales\sw.pak (667,143 B)
  locales\ta.pak (1,720,238 B)
  locales\te.pak (1,591,907 B)
  locales\th.pak (1,337,523 B)
  locales\tr.pak (679,644 B)
  locales\uk.pak (1,185,632 B)
  locales\ur.pak (1,021,689 B)
  locales\vi.pak (808,093 B)
  locales\zh-CN.pak (577,794 B)
  locales\zh-TW.pak (570,098 B)
  resources\app.asar (125,499,313 B)
  resources\asar-integrity.hex (64 B)
  resources\elevate.exe (107,520 B)
  resources.pak (6,876,297 B)
  snapshot_blob.bin (346,432 B)
  v8_context_snapshot.bin (721,176 B)
  vk_swiftshader.dll (5,526,016 B)
  vk_swiftshader_icd.json (106 B)
  vulkan-1.dll (925,696 B)
```

### app.asar extracted
```
  dist\assets\index-C3BEZ3h_.js (4,205,948 B)
  dist\assets\style-BOLoxzN-.css (543,646 B)
  dist\favicon.svg (407 B)
  dist\index.html (2,196 B)
  dist\landing\character-a.jpg (37,189 B)
  dist\landing\character-b.jpg (30,465 B)
  dist\landing\ganyu.jpg (57,422 B)
  dist\landing\kurumi.jpg (37,189 B)
  dist\landing\mahiru.jpg (220,134 B)
  dist\landing\megumi.jpg (158,351 B)
  dist\landing\mika.jpg (545,788 B)
  dist\landing\yuno.jpg (30,465 B)
  dist\landing\zero-two.png (162,334 B)
  dist\logo.png (531,651 B)
  dist\pet\anya_idle0.png (42,743 B)
  dist\pet\anya_spritesheet.webp (1,761,046 B)
  dist\pet\ayaka_idle0.png (47,951 B)
  dist\pet\ayaka_spritesheet.png (2,712,200 B)
  dist\pet\ayaka_spritesheet.webp (2,199,636 B)
  dist\pet\baobao_idle0.png (35,203 B)
  dist\pet\baobao_spritesheet.webp (1,611,218 B)
  dist\pet\chen_idle0.png (42,962 B)
  dist\pet\chen_spritesheet.webp (1,898,170 B)
  dist\pet\conan_idle0.png (37,244 B)
  dist\pet\conan_spritesheet.webp (1,787,624 B)
  dist\pet\furina_idle0.png (44,068 B)
  dist\pet\furina_spritesheet.webp (2,191,112 B)
  dist\pet\ganyu_idle0.png (46,969 B)
  dist\pet\ganyu_spritesheet.png (2,654,394 B)
  dist\pet\ganyu_spritesheet.webp (2,147,874 B)
  dist\pet\hu-tao_idle0.png (53,481 B)
  dist\pet\hu-tao_spritesheet.png (2,989,413 B)
  dist\pet\hu-tao_spritesheet.webp (2,287,678 B)
  dist\pet\kid_idle0.png (42,126 B)
  dist\pet\kid_spritesheet.webp (1,739,404 B)
  dist\pet\klee_idle0.png (50,577 B)
  dist\pet\klee_spritesheet.png (2,827,930 B)
  dist\pet\klee_spritesheet.webp (2,346,614 B)
  dist\pet\lappland_idle0.png (56,289 B)
  dist\pet\lappland_spritesheet.webp (2,316,386 B)
  dist\pet\march-7th_idle0.png (44,966 B)
  dist\pet\march-7th_spritesheet.webp (2,034,726 B)
  dist\pet\new-covenant-exusiai_idle0.png (43,793 B)
  dist\pet\new-covenant-exusiai_spritesheet.webp (2,076,088 B)
  dist\pet\raiden_idle0.png (46,910 B)
  dist\pet\raiden_sprite.png (263,626 B)
  dist\pet\raiden_sprite.webp (61,836 B)
  dist\pet\raiden_spritesheet.webp (2,129,234 B)
  dist\pet\shinchan_idle0.png (43,282 B)
  dist\pet\shinchan_spritesheet.webp (1,847,116 B)
  dist\pet\yoimiya_idle0.png (39,071 B)
  dist\pet\yoimiya_spritesheet.webp (1,862,998 B)
  dist\theme-boot.js (1,032 B)
  dist-electron\index-CyUalY9f.js (439,879 B)
  dist-electron\index-DGzWQhdD.js (312,827 B)
  dist-electron\main.js (36,012 B)
  dist-electron\preload.cjs (3,578 B)
  node_modules\@babel\helper-string-parser\lib\index.js (7,860 B)
  node_modules\@babel\helper-string-parser\lib\index.js.map (21,757 B)
  node_modules\@babel\helper-string-parser\LICENSE (1,106 B)
  node_modules\@babel\helper-string-parser\package.json (758 B)
  node_modules\@babel\helper-validator-identifier\lib\identifier.js (12,543 B)
  node_modules\@babel\helper-validator-identifier\lib\identifier.js.map (26,771 B)
  node_modules\@babel\helper-validator-identifier\lib\index.js (1,362 B)
  node_modules\@babel\helper-validator-identifier\lib\index.js.map (505 B)
  node_modules\@babel\helper-validator-identifier\lib\keyword.js (1,577 B)
  node_modules\@babel\helper-validator-identifier\lib\keyword.js.map (3,842 B)
  node_modules\@babel\helper-validator-identifier\LICENSE (1,106 B)
  node_modules\@babel\helper-validator-identifier\package.json (738 B)
  node_modules\@babel\parser\bin\babel-parser.js (363 B)
  node_modules\@babel\parser\lib\index.js (512,666 B)
  node_modules\@babel\parser\lib\index.js.map (1,434,020 B)
  node_modules\@babel\parser\LICENSE (1,086 B)
  node_modules\@babel\parser\package.json (1,135 B)
  node_modules\@babel\types\lib\asserts\assertNode.js (465 B)
  node_modules\@babel\types\lib\asserts\assertNode.js.map (842 B)
  node_modules\@babel\types\lib\asserts\generated\index.js (45,593 B)
  node_modules\@babel\types\lib\asserts\generated\index.js.map (101,071 B)
  node_modules\@babel\types\lib\ast-types\generated\index.js (49 B)
  node_modules\@babel\types\lib\ast-types\generated\index.js.map (223,851 B)
  ...
```

## 2. IPC surface (preload → main)

(preload not found)

## 3. Secret / sensitive string scan

Total hits: **24** | Non-public config hits: **13**

| File | Line | Label | Match |
|------|------|-------|-------|
| `dist\assets\index-C3BEZ3h_.js` | 122944 | api_key literal (case-insensitive) | `apiKey` |
| `dist\assets\index-C3BEZ3h_.js` | 122984 | api_key literal (case-insensitive) | `apiKey` |
| `dist\assets\index-C3BEZ3h_.js` | 123041 | api_key literal (case-insensitive) | `apiKey` |
| `dist\assets\index-C3BEZ3h_.js` | 123058 | api_key literal (case-insensitive) | `apiKey` |
| `dist\assets\index-C3BEZ3h_.js` | 123333 | api_key literal (case-insensitive) | `apiKey` |
| `dist\assets\index-C3BEZ3h_.js` | 88759 | cloud API host | `154.219.111.30` |
| `dist\assets\index-C3BEZ3h_.js` | 107498 | Sa-Token header name | `lianyu-token` |
| `dist-electron\main.js` | 417 | cloud API host | `154.219.111.30` |
| `dist-electron\main.js` | 419 | SPKI pin prefix | `EdDpp/` |
| `dist-electron\main.js` | 420 | cert fingerprint prefix | `8B:D6:4E:A0` |
| `dist-electron\main.js` | 281 | Sa-Token header name | `lianyu-token` |
| `node_modules\axios\dist\node\axios.cjs` | 3087 | password assignment | `Password =` |
| `node_modules\axios\dist\node\axios.cjs` | 3099 | password assignment | `Password =` |
| `node_modules\axios\dist\node\axios.cjs` | 3099 | password assignment | `password :` |
| `node_modules\axios\dist\node\axios.cjs` | 3489 | password assignment | `password =` |
| `node_modules\axios\dist\node\axios.cjs` | 3494 | password assignment | `Password =` |
| `node_modules\axios\lib\adapters\http.js` | 309 | password assignment | `Password =` |
| `node_modules\axios\lib\adapters\http.js` | 323 | password assignment | `Password =` |
| `node_modules\axios\lib\adapters\http.js` | 324 | password assignment | `password :` |
| `node_modules\axios\lib\adapters\http.js` | 834 | password assignment | `password =` |
| `node_modules\axios\lib\adapters\http.js` | 840 | password assignment | `Password =` |
| `node_modules\element-plus\dist\index.full.js` | 14724 | password assignment | `Password:` |
| `node_modules\element-plus\dist\index.full.min.js` | 21 | password assignment | `Password:` |
| `node_modules\element-plus\lib\components\input\src\input.js` | 128 | password assignment | `Password:` |

## 4. Verdict

**MEDIUM**: Business logic / config strings recoverable; no backend secrets detected.

- Vue/Electron JS is minified but recoverable (beautified in audit dir).
- `.cursor/` rules are NOT in installer (not in electron-builder files list).
