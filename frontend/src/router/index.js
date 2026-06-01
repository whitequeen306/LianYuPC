import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Landing',
    component: () => import('@/pages/LandingPage.vue'),
    meta: { public: true }
  },
  { path: '/encounter', redirect: { path: '/', hash: '#cast' } },
  { path: '/characters', redirect: '/app/characters' },
  { path: '/characters/:id/detail', redirect: to => `/app/characters/${to.params.id}/detail` },
  { path: '/chat/:id', redirect: to => `/app/chat/${to.params.id}` },
  { path: '/group-chat', redirect: '/app/group-chat' },
  { path: '/memory', redirect: '/app/memory' },
  { path: '/profile', redirect: '/app/profile' },
  { path: '/settings', redirect: '/app/settings' },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/pages/LoginPage.vue'),
    meta: { guest: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/pages/RegisterPage.vue'),
    meta: { guest: true }
  },
  {
    path: '/app',
    component: () => import('@/layouts/DefaultLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/pages/HomePage.vue'),
        meta: { titleKey: 'routes.home' }
      },
      {
        path: 'character-square',
        name: 'CharacterSquare',
        component: () => import('@/pages/CharacterSquarePage.vue'),
        meta: { titleKey: 'routes.characterSquare' }
      },
      {
        path: 'characters',
        name: 'Characters',
        component: () => import('@/pages/CharactersPage.vue'),
        meta: { titleKey: 'routes.characters' }
      },
      {
        path: 'characters/:id/detail',
        name: 'CharacterChatDetail',
        component: () => import('@/pages/CharacterChatDetailPage.vue'),
        meta: { titleKey: 'routes.characterDetail' }
      },
      {
        path: 'chat/:id',
        name: 'Chat',
        component: () => import('@/pages/ChatPage.vue'),
        meta: { titleKey: 'routes.chat' }
      },
      {
        path: 'group-chat',
        name: 'GroupChat',
        component: () => import('@/pages/GroupChatPage.vue'),
        meta: { titleKey: 'routes.groupChat' }
      },
      {
        path: 'memory',
        name: 'Memory',
        component: () => import('@/pages/MemoryPage.vue'),
        meta: { titleKey: 'routes.memory' }
      },
      {
        path: 'moments',
        name: 'Moments',
        component: () => import('@/pages/MomentsPage.vue'),
        meta: { titleKey: 'routes.moments' }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/pages/ProfilePage.vue'),
        meta: { titleKey: 'routes.profile' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/pages/SettingsPage.vue'),
        meta: { titleKey: 'routes.settings' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// Auth guard
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('lianyu-token')

  if (to.meta.guest) {
    // Already logged in users shouldn't see login/register
    if (token) {
      return next('/app')
    }
    return next()
  }

  if (to.meta.public) {
    if (to.name === 'Landing' && token) {
      return next('/app')
    }
    // Encounter 等公开页：已登录用户也可访问
    return next()
  }

  // Protected routes require token → 未登录回营销首页
  if (to.meta.requiresAuth && !token) {
    return next({ path: '/', replace: true })
  }

  next()
})

export default router
