"""Regenerate app icon: crisp multi-size ICO, transparent corners, squircle mask."""
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "build" / "logo-source.png"
OUT_PNG = ROOT / "public" / "logo.png"
OUT_ICO = ROOT / "build" / "icon.ico"

ICO_SIZES = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (24, 24), (16, 16)]


def squircle_mask(size: int, radius_ratio: float = 0.223) -> Image.Image:
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


def is_matte_black(r: int, g: int, b: int, a: int) -> bool:
    if a == 0:
        return True
    peak = max(r, g, b)
    if peak > 48:
        return False
    spread = peak - min(r, g, b)
    return spread <= 18


def remove_corner_matte(img: Image.Image) -> Image.Image:
    """Flood-fill opaque black corner matte from edges only."""
    rgba = img.convert("RGBA")
    pixels = rgba.load()
    w, h = rgba.size
    seeds = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    seen = set()
    queue: deque[tuple[int, int]] = deque()

    for x, y in seeds:
        if is_matte_black(*pixels[x, y]):
            queue.append((x, y))
            seen.add((x, y))

    while queue:
        x, y = queue.popleft()
        pixels[x, y] = (0, 0, 0, 0)
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if nx < 0 or ny < 0 or nx >= w or ny >= h:
                continue
            if (nx, ny) in seen:
                continue
            if is_matte_black(*pixels[nx, ny]):
                seen.add((nx, ny))
                queue.append((nx, ny))

    return rgba


def remove_dark_halo(img: Image.Image) -> Image.Image:
    """Strip dark semi-transparent fringe without touching solid artwork."""
    rgba = img.convert("RGBA")
    pixels = rgba.load()
    w, h = rgba.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            if a < 220 and max(r, g, b) <= 42:
                pixels[x, y] = (0, 0, 0, 0)
    return rgba


def peel_dark_edge(img: Image.Image, passes: int = 5, threshold: int = 72) -> Image.Image:
    """Remove dark matte ring left from the old square black background."""
    rgba = img.convert("RGBA")
    pixels = rgba.load()
    w, h = rgba.size
    neighbors = ((-1, 0), (1, 0), (0, -1), (0, 1))

    for _ in range(passes):
        to_clear: list[tuple[int, int]] = []
        for y in range(h):
            for x in range(w):
                r, g, b, a = pixels[x, y]
                if a == 0 or max(r, g, b) > threshold:
                    continue
                for dx, dy in neighbors:
                    nx, ny = x + dx, y + dy
                    if nx < 0 or ny < 0 or nx >= w or ny >= h:
                        to_clear.append((x, y))
                        break
                    if pixels[nx, ny][3] == 0:
                        to_clear.append((x, y))
                        break
        if not to_clear:
            break
        for x, y in to_clear:
            pixels[x, y] = (0, 0, 0, 0)
    return rgba


def apply_mask(img: Image.Image, mask: Image.Image) -> Image.Image:
    rgba = img.convert("RGBA")
    out = Image.new("RGBA", rgba.size, (0, 0, 0, 0))
    out.paste(rgba, (0, 0), mask)
    return out


def center_square(img: Image.Image) -> Image.Image:
    size = min(img.size)
    if img.size == (size, size):
        return img
    left = (img.width - size) // 2
    top = (img.height - size) // 2
    return img.crop((left, top, left + size, top + size))


def build_icon(src: Path) -> Image.Image:
    base = center_square(Image.open(src).convert("RGBA"))
    cleaned = remove_corner_matte(base)
    cleaned = remove_dark_halo(cleaned)
    mask = squircle_mask(base.size[0])
    masked = apply_mask(cleaned, mask)
    masked = peel_dark_edge(masked)
    return masked.filter(ImageFilter.UnsharpMask(radius=1.0, percent=120, threshold=3))


def save_png(img: Image.Image, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    img.save(dest, format="PNG", optimize=False, compress_level=3)


def save_ico(img: Image.Image, dest: Path) -> None:
    master = img
    if master.size[0] < 256:
        master = img.resize((256, 256), Image.Resampling.LANCZOS)
    dest.parent.mkdir(parents=True, exist_ok=True)
    master.save(dest, format="ICO", sizes=ICO_SIZES)


def ensure_source() -> None:
    if SOURCE.is_file():
        return
    import subprocess
    from io import BytesIO

    data = subprocess.check_output(["git", "show", "b78e9f4:frontend/public/logo.png"], cwd=ROOT.parent)
    SOURCE.parent.mkdir(parents=True, exist_ok=True)
    Image.open(BytesIO(data)).save(SOURCE, format="PNG")


def main() -> None:
    ensure_source()
    icon = build_icon(SOURCE)
    save_png(icon, OUT_PNG)
    save_ico(icon, OUT_ICO)

    from PIL import IcoImagePlugin

    entries = IcoImagePlugin.IcoImageFile(OUT_ICO).ico.entry
    dims = [e.dim for e in entries]
    print(f"Wrote {OUT_PNG} ({icon.size[0]}px)")
    print(f"Wrote {OUT_ICO} with sizes: {dims}")


if __name__ == "__main__":
    main()
