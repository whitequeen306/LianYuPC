#!/usr/bin/env python3
"""从现有美术素材生成 NSIS 品牌化位图。

为什么需要它：0.3.2 起安装器换回 electron-builder 的 NSIS（自制 282MB WPF 单文件
安装器会被 Windows 以「未知发布者」拦截，见 docs/refactor-2026-10/client-release.md）。
但用户喜欢原来那个界面，所以把同一套美术素材做成 NSIS 的 MUI 位图：

  installerSidebar.bmp   164x314  欢迎页 / 完成页左侧大图
  installerHeader.bmp    150x57   内页顶部横幅

尺寸是 MUI 的硬要求，给错会被拉伸或裁切。
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "installer" / "LianYu.Installer" / "Assets"
BUILD = ROOT / "frontend" / "build"

SIDEBAR = (164, 314)
HEADER = (150, 57)

# 与 WPF 安装器同一套配色
BG_TOP = (20, 26, 36)
BG_BOTTOM = (11, 16, 24)
ACCENT = (244, 166, 181)
TEXT = (232, 237, 242)
MUTED = (137, 149, 162)


def vertical_gradient(size, top, bottom):
    width, height = size
    img = Image.new("RGB", size, top)
    draw = ImageDraw.Draw(img)
    for y in range(height):
        t = y / max(1, height - 1)
        color = tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        draw.line([(0, y), (width, y)], fill=color)
    return img


def make_sidebar(source: Path) -> Image.Image:
    """裁成 164:314 再缩放，保证不变形。"""
    img = Image.open(source).convert("RGB")
    target_ratio = SIDEBAR[0] / SIDEBAR[1]
    w, h = img.size
    ratio = w / h
    if ratio > target_ratio:
        # 太宽：裁左右
        new_w = int(h * target_ratio)
        left = (w - new_w) // 2
        img = img.crop((left, 0, left + new_w, h))
    else:
        # 太高：裁上下，偏上取（人物头部通常在画面上半部）
        new_h = int(w / target_ratio)
        top = max(0, int((h - new_h) * 0.22))
        img = img.crop((0, top, w, top + new_h))
    return img.resize(SIDEBAR, Image.LANCZOS)


def load_font(size: int):
    for name in ("msyh.ttc", "msyhbd.ttc", "simhei.ttf"):
        path = Path("C:/Windows/Fonts") / name
        if path.is_file():
            try:
                return ImageFont.truetype(str(path), size)
            except OSError:
                continue
    return ImageFont.load_default()


def make_header() -> Image.Image:
    """页头横幅：渐变底 + 图标 + 予念 / YUNIAN。"""
    img = vertical_gradient(HEADER, BG_TOP, BG_BOTTOM)
    draw = ImageDraw.Draw(img)

    # 左侧一条强调色竖线，和 WPF 版的粉色描边呼应
    draw.rectangle([0, 0, 2, HEADER[1]], fill=ACCENT)

    icon_path = ASSETS / "icon.ico"
    icon_size = 34
    icon_x, icon_y = 10, (HEADER[1] - icon_size) // 2
    try:
        icon = Image.open(icon_path)
        # ico 里通常有多个尺寸，选最接近的再缩放
        best = None
        for size in sorted(getattr(icon, "info", {}).get("sizes", [])):
            best = size
        icon = icon.convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
        img.paste(icon, (icon_x, icon_y), icon)
    except Exception as exc:  # noqa: BLE001 - 图标缺失不该阻断打包
        print(f"[warn] icon 读取失败，跳过：{exc}")

    text_x = icon_x + icon_size + 8
    draw.text((text_x, 8), "予念", font=load_font(17), fill=TEXT)
    draw.text((text_x, 32), "Y U N I A N", font=load_font(9), fill=MUTED)
    return img


def main() -> None:
    BUILD.mkdir(parents=True, exist_ok=True)

    sidebar = make_sidebar(ASSETS / "mahiru.jpg")
    sidebar_path = BUILD / "installerSidebar.bmp"
    sidebar.save(sidebar_path, "BMP")
    print(f"{sidebar_path}  {sidebar.size}")

    header = make_header()
    header_path = BUILD / "installerHeader.bmp"
    header.save(header_path, "BMP")
    print(f"{header_path}  {header.size}")

    for path, expect in ((sidebar_path, SIDEBAR), (header_path, HEADER)):
        with Image.open(path) as check:
            assert check.size == expect, f"{path} 尺寸 {check.size} != {expect}"
            assert check.format == "BMP", f"{path} 不是 BMP"
    print("OK: 两张位图尺寸与格式都符合 MUI 要求")


if __name__ == "__main__":
    main()
