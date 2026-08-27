<script setup lang="ts">
import { reactive, ref } from 'vue'; import { useRouter } from 'vue-router'; import { useAuthStore } from '../stores/auth'
const router = useRouter(); const auth = useAuthStore(); const submitted = ref(false); const form = reactive({ identifier: '', password: '' })
async function submit() { submitted.value = true; try { await auth.login(form); await router.push('/profiles') } catch { /* safe store error */ } }
</script>
<template><main class="auth-page"><section class="auth-panel"><p class="eyebrow">Resume Matching</p><h1>Welcome back</h1><form @submit.prevent="submit"><label>Username or email<input v-model="form.identifier" autocomplete="username" /></label><label>Password<input v-model="form.password" type="password" autocomplete="current-password" /></label><p v-if="auth.error && submitted" class="error">{{ auth.error }}</p><button type="submit" :disabled="auth.loading">Sign in</button></form><RouterLink to="/register">Create an account</RouterLink></section></main></template>
