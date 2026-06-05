/** Codex 8×9 atlas: 1536×1872, cell 192×208 */
export const PET_FRAME_W = 192
export const PET_FRAME_H = 208
export const PET_COLS = 8

/** Row layout per Codex pet spec（所有标准桌宠共用，9 行动作均已接入） */
export const PET_ANIMATIONS = {
  idle: { row: 0, frames: 6, fps: 6, loop: true },           // 默认待机
  'run-right': { row: 1, frames: 8, fps: 12, loop: true },   // 向右拖
  'run-left': { row: 2, frames: 8, fps: 12, loop: true },   // 向左拖
  wave: { row: 3, frames: 4, fps: 8, loop: false },           // 单击打招呼
  jump: { row: 4, frames: 5, fps: 10, loop: false },          // 双击 / 新消息
  failed: { row: 5, frames: 8, fps: 8, loop: false },         // 待机随机出糗
  waiting: { row: 6, frames: 6, fps: 5, loop: true },          // 长按准备
  running: { row: 7, frames: 6, fps: 10, loop: true },        // 拖完减速 / 待机随机
  review: { row: 8, frames: 6, fps: 5, loop: false },         // 右键查看
}
