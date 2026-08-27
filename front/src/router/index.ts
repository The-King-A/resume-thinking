import { createRouter, createWebHistory } from 'vue-router'; import { useAuthStore } from '../stores/auth'
export const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', redirect: '/profiles' }, { path: '/login', component: () => import('../views/LoginView.vue'), meta: { guest: true } }, { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { guest: true } }, { path: '/profiles', component: () => import('../views/ModelProfilesView.vue'), meta: { requiresAuth: true } }] })
router.beforeEach((to) => { const auth = useAuthStore(); if (to.meta.requiresAuth && !auth.isAuthenticated) return '/login'; if (to.meta.guest && auth.isAuthenticated) return '/profiles' })
export default router
