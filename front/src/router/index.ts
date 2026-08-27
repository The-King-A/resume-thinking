import { createRouter, createWebHistory } from 'vue-router'
import { registerAuthSessionExpiredHandler } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { UserRole } from '../api/contracts'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/resumes' },
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { guest: true } },
    { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { guest: true } },
    { path: '/profiles', component: () => import('../views/ModelProfilesView.vue'), meta: { requiresAuth: true } },
    { path: '/resumes', component: () => import('../views/ResumeListView.vue'), meta: { requiresAuth: true } },
    { path: '/match', component: () => import('../views/UploadMatchView.vue'), meta: { requiresAuth: true } },
    { path: '/matches/:taskId', component: () => import('../views/MatchResultView.vue'), meta: { requiresAuth: true } },
    { path: '/recovery', component: () => import('../views/RecoveryView.vue'), meta: { requiresAuth: true } },
    { path: '/admin/recovery', component: () => import('../views/AdminRecoveryView.vue'), meta: { requiresAuth: true, roles: ['ADMIN'] satisfies UserRole[] } },
  ],
})

registerAuthSessionExpiredHandler(() => {
  if (router.currentRoute.value.path !== '/login') void router.push('/login').catch(() => undefined)
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  auth.bindHttpSession()
  if (to.meta.requiresAuth && !auth.isAuthenticated) return '/login'
  if (to.meta.guest && auth.isAuthenticated) return '/profiles'
  const roles = to.meta.roles as UserRole[] | undefined
  if (roles && (!auth.user || !roles.includes(auth.user.role))) return '/resumes'
})

export default router
