export function clampLauncherBoundsToWorkArea(bounds, workArea, options = {}) {
  const axis = options.axis || 'both'
  let x = bounds.x
  let y = bounds.y

  if (axis !== 'y') {
    if (x + bounds.width > workArea.x + workArea.width) x = workArea.x + workArea.width - bounds.width
    if (x < workArea.x) x = workArea.x
  }
  if (axis !== 'x') {
    if (y + bounds.height > workArea.y + workArea.height) y = workArea.y + workArea.height - bounds.height
    if (y < workArea.y) y = workArea.y
  }

  return { x: Math.round(x), y: Math.round(y) }
}

export function isLauncherWithinWorkArea(bounds, workArea) {
  const right = bounds.x + bounds.width
  const bottom = bounds.y + bounds.height

  return bounds.x >= workArea.x
    && bounds.y >= workArea.y
    && right <= workArea.x + workArea.width
    && bottom <= workArea.y + workArea.height
}
