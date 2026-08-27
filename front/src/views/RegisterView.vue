<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { UserRole } from '../api/contracts'
const router = useRouter(); const auth = useAuthStore(); const submitted = ref(false)
const form = reactive({ username: '', email: '', password: '', role: 'USER' as UserRole })
const adminSelected = ref(false)
async function submit() { submitted.value = true; form.role = adminSelected.value ? 'ADMIN' : 'USER'; try { await auth.register({ ...form }); await router.push('/profiles') } catch { /* store exposes safe message */ } }
</script>
<template>
  <main class="auth-page"><section class="auth-panel"><p class="eyebrow">Resume Matching</p><h1>Create account</h1><p class="muted">Set up your workspace and model access.</p>
    <form @submit.prevent="submit">
      <label>Username<input v-model="form.username" autocomplete="username" /></label>
      <label>Email<input v-model="form.email" type="email" autocomplete="email" /></label>
      <label>Password<input v-model="form.password" type="password" autocomplete="new-password" /></label>
      <fieldset><legend>Account role</legend><label><input type="radio" value="USER" v-model="form.role" data-test="role-user" @change="adminSelected = false" /> User</label><label><input type="checkbox" v-model="adminSelected" data-test="role-admin" /> Administrator</label><p class="notice">Administrators can process recovery records across owners. This is a global recovery scope, not a demo-only mode; use it only where that access is intended.</p></fieldset>
      <p v-if="auth.error && submitted" class="error">{{ auth.error }}</p><button data-test="register-submit" type="submit" @click.prevent="submit" :disabled="auth.loading">Create account</button>
    </form><RouterLink to="/login">Already have an account? Sign in</RouterLink>
  </section></main>
</template>
