"""Regenerate app icon from the YuNian rounded-rect artwork.

源图格式：白底（或透明底）+ 圆角方形立绘（2026-09 更名「予念」后的新图标）。
管线：四角 flood-fill 去掉外围白边 → 轻微腐蚀+羽化修抗锯齿边 → 裁到内容 bbox
→ 居中方形 → squircle 圆角遮罩（四角透明，与任务栏/托盘圆角对齐）→
512px PNG（in-app/启动页/favicon）+ 多尺寸 ICO（任务栏/托盘/exe/安装器）。

输出：
  frontend/public/logo.png                 in-app 透明圆角 logo
  frontend/build/icon.ico                  electron-builder / dev 托盘 / 管理端
  frontend/public/icon.ico                 vite 拷进 dist/ 供打包后托盘/窗口取用
  installer/LianYu.Installer/Assets/       WPF 安装器标题栏 logo + 程序图标
"""
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "build" / "logo-source.png"
OUT_PNG = ROOT / "public" / "logo.png"
OUT_ICO = ROOT / "build" / "icon.ico"
# 同时往 public/ 放一份 icon.ico：vite 会把 public/ 全量拷进 dist/，于是打包后的
# asar 里有 dist/icon.ico 可供运行时托盘/窗口图标取用（build/icon.ico 不会进 asar）。
OUT_ICO_PUBLIC = ROOT / "public" / "icon.ico"
INSTALLER_ASSETS = ROOT.parent / "installer" / "LianYu.Installer" / "Assets"
OUT_INSTALLER_PNG = INSTALLER_ASSETS / "logo.png"
OUT_INSTALLER_ICO = INSTALLER_ASSETS / "icon.ico"

# 判定「白边」的阈值：源图外围是纯白/近白背景
WHITE_THRESH = 238
# squircle 圆角比例（沿用旧管线取值，与任务栏圆角视觉对齐）
MASK_RADIUS_RATIO = 0.223
OUTPUT_SIZE = 512

ICO_SIZES = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (24, 24), (16, 16)]


def squircle_mask(size: int, radius_ratio: float = MASK_RADIUS_RATIO) -> Image.Image:
    """iOS-like continuous corner radius."""
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    inset = max(1, int(size * 0.008))
    radius = int(size * radius_ratio)
    draw.rounded_rectangle(
        (inset, inset, size - inset - 1, size - inset - 1),
        radius=radius,
        fill=255,
    )
    return mask


def is_white(r: int, g: int, b: int, a: int) -> bool:
    if a == 0:
        return True
    return r >= WHITE_THRESH and g >= WHITE_THRESH and b >= WHITE_THRESH


def flood_white_margins(img: Image.Image) -> Image.Image:
    """从四条边 flood-fill 近白像素 → 透明（仅清外围白边；图内白色爱心/蝴蝶结
    被立绘包围、不与边缘连通，不受影响）。"""
    rgba = img.convert("RGBA")
    pixels = rgba.load()
    w, h = rgba.size
    seen = bytearray(w * h)
    queue: deque[tuple[int, int]] = deque()

    def seed(x: int, y: int) -> None:
        if is_white(*pixels[x, y]) and not seen[y * w + x]:
            seen[y * w + x] = 1
            queue.append((x, y))

    for x in range(w):
        seed(x, 0)
        seed(x, h - 1)
    for y in range(h):
        seed(0, y)
        seed(w - 1, y)

    while queue:
        x, y = queue.popleft()
        pixels[x, y] = (0, 0, 0, 0)
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if nx < 0 or ny < 0 or nx >= w or ny >= h:
                continue
            if not seen[ny * w + nx] and is_white(*pixels[nx, ny]):
                seen[ny * w + nx] = 1
                queue.append((nx, ny))

    return rgba


def build_icon(src: Path) -> Image.Image:
    base = Image.open(src).convert("RGBA")
    cleaned = flood_white_margins(base)
    # 白边→立绘交界处的抗锯齿像素偏白：alpha 先腐蚀 1px 再羽化，边缘干净不毛边
    alpha = cleaned.getchannel("A")
    alpha = alpha.filter(ImageFilter.MinFilter(3)).filter(ImageFilter.GaussianBlur(1.2))
    cleaned.putalpha(alpha)
    bbox = alpha.getbbox()
    if not bbox:
        raise SystemExit("flood-fill 后没有剩余内容，请检查源图是否为白边圆角图")
    cleaned = cleaned.crop(bbox)
    cleaned = center_square(cleaned)
    mask = squircle_mask(cleaned.size[0])
    out = Image.new("RGBA", cleaned.size, (0, 0, 0, 0))
    out.paste(cleaned, (0, 0), mask)
    return out.resize((OUTPUT_SIZE, OUTPUT_SIZE), Image.Resampling.LANCZOS)


def center_square(img: Image.Image) -> Image.Image:
    size = min(img.size)
    if img.size == (size, size):
        return img
    left = (img.width - size) // 2
    top = (img.height - size) // 2
    return img.crop((left, top, left + size, top + size))


def save_png(img: Image.Image, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    img.save(dest, format="PNG", optimize=False, compress_level=3)


def save_ico(img: Image.Image, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    img.save(dest, format="ICO", sizes=ICO_SIZES)


def main() -> None:
    if not SOURCE.is_file():
        raise SystemExit(f"缺少源图 {SOURCE}（白边圆角方形立绘）")
    icon = build_icon(SOURCE)
    save_png(icon, OUT_PNG)
    save_ico(icon, OUT_ICO)
    save_ico(icon, OUT_ICO_PUBLIC)
    save_png(icon, OUT_INSTALLER_PNG)
    save_ico(icon, OUT_INSTALLER_ICO)

    from PIL import IcoImagePlugin

    entries = IcoImagePlugin.IcoImageFile(OUT_ICO).ico.entry
    dims = [e.dim for e in entries]
    print(f"Wrote {OUT_PNG} ({OUTPUT_SIZE}px, transparent squircle)")
    print(f"Wrote {OUT_ICO} with sizes: {dims}")
    print(f"Wrote {OUT_ICO_PUBLIC}")
    print(f"Wrote {OUT_INSTALLER_PNG} / {OUT_INSTALLER_ICO}")


if __name__ == "__main__":
    main()
