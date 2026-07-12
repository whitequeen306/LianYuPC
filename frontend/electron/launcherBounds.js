export function clampLauncherBoundsToWorkArea(bounds, workArea, options = {}) {
  const axis = options.axis || 'both'
  const bottomOverflow = options.bottomOverflow || 0
  let x = bounds.x
  let y = bounds.y

  if (axis !== 'y') {
    if (x + bounds.width > workArea.x + workArea.width) x = workArea.x + workArea.width - bounds.width
    if (x < workArea.x) x = workArea.x
  }
  if (axis !== 'x') {
    const maxBottom = workArea.y + workArea.height + bottomOverflow
    if (y + bounds.height > maxBottom) y = maxBottom - bounds.height
    if (y < workArea.y) y = workArea.y
  }

  return { x: Math.round(x), y: Math.round(y) }
}

export function isLauncherWithinWorkArea(bounds, workArea, options = {}) {
  const bottomOverflow = options.bottomOverflow || 0
  const right = bounds.x + bounds.width
  const bottom = bounds.y + bounds.height
  const maxBottom = workArea.y + workArea.height + bottomOverflow

  return bounds.x >= workArea.x
    && bounds.y >= workArea.y
    && right <= workArea.x + workArea.width
    && bottom <= maxBottom
}
