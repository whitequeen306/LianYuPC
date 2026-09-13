import fs from 'fs'
import { execFileSync } from 'child_process'

const COLS = 8
const ROWS = 9
const CELL_W = 192
const CELL_H = 208
const ATLAS_W = COLS * CELL_W
const ATLAS_H = ROWS * CELL_H

const FRAMES = [6, 8, 8, 4, 5, 8, 6, 6, 6]

const buf = Buffer.alloc(ATLAS_W * ATLAS_H * 4, 0)

function px(gx, gy, x, y, r, g, b, a = 255) {
  if (x < 0 || x >= CELL_W || y < 0 || y >= CELL_H) return
  const cx = gx * CELL_W + x
  const cy = gy * CELL_H + y
  const i = (cy * ATLAS_W + cx) * 4
  const ba = a / 255
  if (ba < 1 && buf[i + 3] > 0) {
    // alpha blend over existing
    const ea = buf[i + 3] / 255
    const na = ba + ea * (1 - ba)
    r = (r * ba + buf[i] * ea * (1 - ba)) / na
    g = (g * ba + buf[i + 1] * ea * (1 - ba)) / na
    b = (b * ba + buf[i + 2] * ea * (1 - ba)) / na
    a = na * 255
  }
  buf[i] = r
  buf[i + 1] = g
  buf[i + 2] = b
  buf[i + 3] = a
}

function rect(gx, gy, x, y, w, h, r, g, b, a = 255) {
  for (let dy = 0; dy < h; dy++)
    for (let dx = 0; dx < w; dx++)
      px(gx, gy, x + dx, y + dy, r, g, b, a)
}

function ellipse(gx, gy, cx, cy, rx, ry, r, g, b, a = 255) {
  for (let y = -ry; y <= ry; y++)
    for (let x = -rx; x <= rx; x++)
      if ((x * x) / (rx * rx) + (y * y) / (ry * ry) <= 1)
        px(gx, gy, Math.round(cx + x), Math.round(cy + y), r, g, b, a)
}

function line(gx, gy, x1, y1, x2, y2, r, g, b, a = 255, width = 1) {
  const dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1)
  const sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1
  let err = dx - dy, x = x1, y = y1
  while (true) {
    for (let wy = 0; wy < width; wy++)
      for (let wx = 0; wx < width; wx++)
        px(gx, gy, x + wx, y + wy, r, g, b, a)
    if (x === x2 && y === y2) break
    const e2 = 2 * err
    if (e2 > -dy) { err -= dy; x += sx }
    if (e2 < dx) { err += dx; y += sy }
  }
}

function triangle(gx, gy, x1, y1, x2, y2, x3, y3, r, g, b, a = 255) {
  // bounding box
  const minX = Math.min(x1, x2, x3), maxX = Math.max(x1, x2, x3)
  const minY = Math.min(y1, y2, y3), maxY = Math.max(y1, y2, y3)
  const area = Math.abs((x2 - x1) * (y3 - y1) - (x3 - x1) * (y2 - y1))
  for (let y = minY; y <= maxY; y++) {
    for (let x = minX; x <= maxX; x++) {
      const a1 = Math.abs((x1 - x) * (y2 - y) - (x2 - x) * (y1 - y))
      const a2 = Math.abs((x2 - x) * (y3 - y) - (x3 - x) * (y2 - y))
      const a3 = Math.abs((x3 - x) * (y1 - y) - (x1 - x) * (y3 - y))
      if (Math.abs(a1 + a2 + a3 - area) < 0.5)
        px(gx, gy, x, y, r, g, b, a)
    }
  }
}

// Extracted from reference image
const PAL = {
  bg: [245, 235, 237],
  hair: [240, 192, 224],
  hairD: [224, 176, 208],
  hairH: [250, 215, 232],
  skin: [255, 235, 226],
  skinS: [248, 208, 195],
  dress: [255, 250, 252],
  dressP: [244, 190, 220],
  dressD: [224, 160, 192],
  eye: [138, 90, 110],
  eyeH: [255, 255, 255],
  mouth: [224, 120, 140],
  cheek: [255, 180, 190],
  flower: [244, 190, 220],
  flowerC: [255, 230, 150],
  ribbon: [224, 160, 192],
  butterfly: [244, 190, 220],
  boot: [176, 128, 160],
  outline: [160, 112, 144],
}

function drawElysia(gx, gy, frame, row) {
  let bobY = 0, leanX = 0, armR = 0, armL = 0, legPhase = 0

  switch (row) {
    case 0: bobY = Math.round(Math.sin(frame * 1.0) * 2); break
    case 1: leanX = 3; legPhase = frame % 4; break
    case 2: leanX = -3; legPhase = frame % 4; break
    case 3: armR = Math.round(22 - Math.abs(frame - 1.5) * 7); break
    case 4: bobY = -Math.round(Math.sin(frame * 0.8 + 0.5) * 7); break
    case 5: leanX = Math.round(Math.sin(frame * 1.0) * 7); bobY = 2; break
    case 6: bobY = Math.round(Math.sin(frame * 1.5) * 1); break
    case 7: legPhase = frame % 4; bobY = Math.round(Math.sin(frame * 1.5) * 1); break
    case 8: armR = frame < 3 ? 18 : 6; break
  }

  const cx = 96 + leanX
  const headCY = 78 + bobY
  const bodyTop = 122 + bobY
  const bodyBot = 168 + bobY

  // soft shadow
  ellipse(gx, gy, cx, bodyBot + 18, 32, 7, 0, 0, 0, 50)

  // Back hair (long flowing)
  ellipse(gx, gy, cx, headCY + 16, 44, 52, ...PAL.hairD)
  // Side locks
  rect(gx, gy, cx - 46, headCY - 10, 16, 70 + bobY, ...PAL.hair)
  rect(gx, gy, cx + 30, headCY - 10, 16, 70 + bobY, ...PAL.hair)

  // Body / dress torso
  ellipse(gx, gy, cx, (bodyTop + bodyBot) / 2, 26, 34, ...PAL.dress)
  // Pink ribbon/sash
  rect(gx, gy, cx - 20, bodyTop + 12, 40, 5, ...PAL.dressP)
  rect(gx, gy, cx - 18, bodyTop + 18, 36, 3, ...PAL.dressD)

  // Skirt with gentle flare
  for (let y = 0; y < 24; y++) {
    const sw = 22 + Math.round(y * 1.1)
    rect(gx, gy, cx - sw, bodyBot - 4 + y, sw * 2, 1, ...PAL.dress)
  }
  rect(gx, gy, cx - 48, bodyBot + 18, 96, 3, ...PAL.dressP)

  // Arms
  line(gx, gy, cx - 22, bodyTop + 6, cx - 30 - armL, bodyTop + 26 + armL, ...PAL.skin, 255, 2)
  line(gx, gy, cx + 22, bodyTop + 6, cx + 30 + armR, bodyTop + 26 - armR, ...PAL.skin, 255, 2)

  // Legs
  const lOff = legPhase === 0 || legPhase === 2 ? 0 : (legPhase === 1 ? 5 : -5)
  rect(gx, gy, cx - 11, bodyBot + 20, 9, 18, ...PAL.skin)
  rect(gx, gy, cx + 2, bodyBot + 20 + lOff, 9, 18, ...PAL.skin)
  // Boots
  rect(gx, gy, cx - 13, bodyBot + 36, 13, 7, ...PAL.boot)
  rect(gx, gy, cx + 2, bodyBot + 36 + lOff, 13, 7, ...PAL.boot)

  // Head - larger, rounder
  ellipse(gx, gy, cx, headCY, 36, 38, ...PAL.skin)

  // Front hair / bangs
  ellipse(gx, gy, cx, headCY - 22, 38, 26, ...PAL.hair)
  // Highlight
  rect(gx, gy, cx - 18, headCY - 28, 8, 16, ...PAL.hairH, 160)
  rect(gx, gy, cx + 8, headCY - 26, 6, 12, ...PAL.hairH, 160)

  // Eyes - bigger and cuter
  const eyeY = headCY + 2
  ellipse(gx, gy, cx - 14, eyeY, 7, 9, ...PAL.eye)
  ellipse(gx, gy, cx + 14, eyeY, 7, 9, ...PAL.eye)
  // Eye highlights
  ellipse(gx, gy, cx - 12, eyeY - 2, 3, 3, ...PAL.eyeH)
  ellipse(gx, gy, cx + 16, eyeY - 2, 3, 3, ...PAL.eyeH)

  // Cheeks
  ellipse(gx, gy, cx - 22, eyeY + 8, 6, 4, ...PAL.cheek, 160)
  ellipse(gx, gy, cx + 22, eyeY + 8, 6, 4, ...PAL.cheek, 160)

  // Smile
  line(gx, gy, cx - 7, headCY + 16, cx - 2, headCY + 19, ...PAL.mouth, 255, 2)
  line(gx, gy, cx - 2, headCY + 19, cx + 3, headCY + 16, ...PAL.mouth, 255, 2)

  // Flower on right side of hair
  for (let i = 0; i < 5; i++) {
    const angle = (i * 72 * Math.PI) / 180
    const fx = Math.round(cx + 26 + Math.cos(angle) * 5)
    const fy = Math.round(headCY - 22 + Math.sin(angle) * 5)
    ellipse(gx, gy, fx, fy, 4, 4, ...PAL.flower)
  }
  ellipse(gx, gy, cx + 26, headCY - 22, 3, 3, ...PAL.flowerC)

  // Small butterfly near head
  ellipse(gx, gy, cx - 32, headCY - 12, 6, 8, ...PAL.butterfly)
  ellipse(gx, gy, cx - 32, headCY - 12, 3, 4, ...PAL.eyeH, 200)

  // Soft outline on hair edges (subtle)
  ellipse(gx, gy, cx, headCY - 22, 40, 28, ...PAL.outline, 60)
}

for (let row = 0; row < ROWS; row++) {
  for (let f = 0; f < FRAMES[row]; f++) {
    drawElysia(f, row, f, row)
  }
}

const rawPath = 'C:\\Users\\hp\\Desktop\\LianYu-PC\\artifacts\\elysia_atlas_rgba.raw'
fs.writeFileSync(rawPath, buf)
console.log('raw_written=' + buf.length)

const atlasPng = 'C:\\Users\\hp\\Desktop\\LianYu-PC\\artifacts\\elysia_atlas_full.png'
const webpPath = 'C:\\Users\\hp\\Desktop\\LianYu-PC\\frontend\\public\\pet\\elysia_spritesheet.webp'

execFileSync('ffmpeg', [
  '-y', '-hide_banner', '-loglevel', 'error',
  '-f', 'rawvideo', '-pix_fmt', 'rgba',
  '-s', `${ATLAS_W}x${ATLAS_H}`,
  '-i', rawPath,
  '-frames:v', '1',
  '-c:v', 'png',
  atlasPng
], { stdio: 'inherit' })
console.log('png_atlas_written=' + atlasPng)

execFileSync('ffmpeg', [
  '-y', '-hide_banner', '-loglevel', 'error',
  '-i', atlasPng,
  '-c:v', 'libwebp',
  '-pix_fmt', 'bgra',
  webpPath
], { stdio: 'inherit' })
console.log('webp_written=' + webpPath)

const idlePng = 'C:\\Users\\hp\\Desktop\\LianYu-PC\\frontend\\public\\pet\\elysia_idle0.png'
execFileSync('ffmpeg', [
  '-y', '-hide_banner', '-loglevel', 'error',
  '-i', atlasPng,
  '-vf', `crop=${CELL_W}:${CELL_H}:0:0`,
  '-frames:v', '1',
  '-c:v', 'png',
  idlePng
], { stdio: 'inherit' })
console.log('png_preview_written=' + idlePng)
