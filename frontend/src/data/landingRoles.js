/** 首页展示的全部角色（6 位） */
export const LANDING_ROLES_ALL = [
  {
    id: 'kurumi',
    name: '时崎狂三',
    shortName: '狂三',
    src: '/landing/kurumi.jpg',
    line: '呵呵，又见面了呢。今晚的时间……要不要先欠我一点？',
  },
  {
    id: 'yuno',
    name: '我妻由乃',
    shortName: '由乃',
    src: '/landing/yuno.jpg',
    line: '你来了呀……今天也要一直看着我，好吗？',
  },
  {
    id: 'ganyu',
    name: '甘雨',
    shortName: '甘雨',
    src: '/landing/ganyu.jpg',
    line: '你好。若你不嫌弃，我想认真听你把话说完。',
  },
  {
    id: 'mika',
    name: '圣园未花',
    shortName: '未花',
    src: '/landing/mika.jpg',
    line: '老师老师～今天也要一起元气满满哦！我会一直为你加油的！',
  },
  {
    id: 'mahiru',
    name: '椎名真昼',
    shortName: '真昼',
    src: '/landing/mahiru.jpg',
    line: '你好。……今天也请慢慢来，我会一直在你身边。',
  },
  {
    id: 'megumi',
    name: '加藤惠',
    shortName: '加藤惠',
    src: '/landing/megumi.jpg',
    line: '嗯。……你来了啊。想说什么的话，我可以听。',
  },
]

/** 角色区块：狂三、真昼、加藤惠、未花 */
export const ENCOUNTER_ROLES = [
  {
    id: 'kurumi',
    name: '时崎狂三',
    src: '/landing/kurumi.jpg',
    line: '呵呵，命运的红线又牵到我们了呢。今晚……愿意把时间分给我一点吗？',
  },
  {
    id: 'mahiru',
    name: '椎名真昼',
    src: '/landing/mahiru.jpg',
    line: '你好。……如果不介意，可以从一句「今天过得怎么样」开始吗？我会认真听的。',
  },
  {
    id: 'megumi',
    name: '加藤惠',
    src: '/landing/megumi.jpg',
    line: '……嗯。我就在这里。不用急着说，你想聊什么都可以。',
  },
  {
    id: 'mika',
    name: '圣园未花',
    src: '/landing/mika.jpg',
    line: '呀，你来了！……今天也把一点点好心情，分给我好不好？我会超级开心的！',
  },
]

export function cloneRolesForShowcase(roles) {
  return roles.map((r) => ({ ...r, imgError: false }))
}

export function preloadRoleImages(roles) {
  roles.forEach((r) => {
    const img = new Image()
    img.src = r.src
  })
}
