/** Codex 8×9 atlas: 1536×1872, cell 192×208 */
export const PET_FRAME_W = 192
export const PET_FRAME_H = 208
export const PET_COLS = 8

/** Row layout per Codex pet spec（所有标准桌宠共用） */
export const PET_ANIMATIONS = {
  idle: { row: 0, frames: 6, fps: 6, loop: true },
  'run-right': { row: 1, frames: 8, fps: 12, loop: true },
  'run-left': { row: 2, frames: 8, fps: 12, loop: true },
  wave: { row: 3, frames: 4, fps: 8, loop: false },
  jump: { row: 4, frames: 5, fps: 10, loop: false },
  failed: { row: 5, frames: 8, fps: 8, loop: false },
  waiting: { row: 6, frames: 6, fps: 5, loop: true },
  running: { row: 7, frames: 6, fps: 10, loop: true },
  review: { row: 8, frames: 6, fps: 5, loop: false },
}
