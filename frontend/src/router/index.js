import { createRouter, createWebHashHistory } from 'vue-router'
import { readToken, syncToken, syncSetTokenCache } from '@/utils/secureToken'
import { useUserStore } from '@/stores/user'

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
  { path: '/qq-bridge', redirect: '/app/qq-bridge' },
  { path: '/about', redirect: '/app/about' },
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
    path: '/launcher',
    name: 'Launcher',
    component: () => import('@/pages/LauncherPage.vue'),
    meta: { public: true, chromeless: true }
  },
  {
    path: '/launcher/pick',
    name: 'LauncherPick',
    component: () => import('@/pages/LauncherPickPage.vue'),
    meta: { requiresAuth: true, chromeless: true }
  },
  {
    path: '/quick',
    component: () => import('@/layouts/QuickChatLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: 'chat/:id',
        name: 'QuickChat',
        component: () => import('@/pages/QuickChatPage.vue'),
        meta: { quickChat: true }
      }
    ]
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
        meta: { titleKey: 'routes.chat', immersive: true }
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
        path: 'diary',
        name: 'Diary',
        component: () => import('@/pages/DiaryPage.vue'),
        meta: { titleKey: 'routes.diary' }
      },
      {
        path: 'moments',
        name: 'Moments',
        component: () => import('@/pages/MomentsPage.vue'),
        meta: { titleKey: 'routes.moments' }
      },
      {
        path: 'community',
        name: 'Community',
        component: () => import('@/pages/CommunityPage.vue'),
        meta: { titleKey: 'routes.community' }
      },
      {
        path: 'users/:userId',
        name: 'UserPublic',
        component: () => import('@/pages/UserPublicPage.vue'),
        meta: { titleKey: 'routes.userPublic' }
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
        meta: { titleKey: 'routes.settings', hideDock: true }
      },
      {
        path: 'qq-bridge',
        name: 'QqBridge',
        component: () => import('@/pages/QqBridgePage.vue'),
        meta: { titleKey: 'routes.qqBridge', hideDock: true }
      },
      {
        path: 'about',
        name: 'About',
        component: () => import('@/pages/AboutPage.vue'),
        meta: { titleKey: 'routes.about', hideDock: true }
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

// Auth guard — 须先 await readToken()，且仅有 token 无 userId 时仍允许进登录页
router.beforeEach(async (to, from, next) => {
  await readToken()
  const token = syncToken()
  const userStore = useUserStore()
  if (token && !userStore.token) {
    userStore.token = token
    syncSetTokenCache(token)
  }
  const hasStartupToken = !!token

  if (to.meta.guest) {
    if (hasStartupToken) {
      return next('/app')
    }
    return next()
  }

  if (to.meta.public) {
    if (to.name === 'Landing' && hasStartupToken) {
      return next('/app')
    }
    return next()
  }

  if (to.meta.requiresAuth && !token) {
    return next({ path: '/', replace: true })
  }

  next()
})

export default router
