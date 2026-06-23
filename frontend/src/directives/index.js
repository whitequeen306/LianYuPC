import { bubbleBtnDirective } from './bubbleBtn'
import { tiltDirective } from './tilt'

export function registerDirectives(app) {
  app.directive('bubble-btn', bubbleBtnDirective)
  app.directive('tilt', tiltDirective)
}

export default {
  install(app) {
    registerDirectives(app)
  },
}
