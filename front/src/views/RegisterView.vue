<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { UserRole } from '../api/contracts'
const router = useRouter(); const auth = useAuthStore(); const submitted = ref(false)
const form = reactive({ username: '', email: '', password: '', role: 'USER' as UserRole })
const adminSelected = ref(false); const formRef = ref<FormInstance>(); const validationMessage = ref(''); const rules: FormRules = { username: [{ required: true, message: 'Username is required', trigger: 'blur' }, { min: 3, message: 'Username must be at least 3 characters', trigger: 'blur' }, { max: 64, message: 'Username must be at most 64 characters', trigger: 'blur' }, { pattern: /^[A-Za-z0-9._-]+$/, message: 'Username may contain only letters, numbers, dot, underscore, or hyphen', trigger: 'blur' }], email: [{ required: true, message: 'Email is required', trigger: 'blur' }, { type: 'email', message: 'Enter a valid email', trigger: 'blur' }, { max: 254, message: 'Email must be at most 254 characters', trigger: 'blur' }], password: [{ required: true, message: 'Password is required', trigger: 'blur' }, { min: 12, message: 'Password must be at least 12 characters', trigger: 'blur' }, { max: 128, message: 'Password must be at most 128 characters', trigger: 'blur' }] }
async function submit() {
  submitted.value = true
  form.role = adminSelected.value ? 'ADMIN' : 'USER'
  validationMessage.value = ''
  const elementValid = await formRef.value?.validate().catch(() => false)
  if (form.username.length < 3) validationMessage.value = 'Username must be at least 3 characters'
  else if (form.username.length > 64) validationMessage.value = 'Username must be at most 64 characters'
  else if (!/^[A-Za-z0-9._-]+$/.test(form.username)) validationMessage.value = 'Username may contain only letters, numbers, dot, underscore, or hyphen'
  else if (!form.email) validationMessage.value = 'Email is required'
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) validationMessage.value = 'Enter a valid email'
  else if (form.email.length > 254) validationMessage.value = 'Email must be at most 254 characters'
  else if (form.password.length < 12) validationMessage.value = 'Password must be at least 12 characters'
  else if (form.password.length > 128) validationMessage.value = 'Password must be at most 128 characters'
  if (form.username.length === 0) validationMessage.value = 'Username is required'
  if (elementValid === false || validationMessage.value) return
  try { await auth.register({ ...form }); await router.push('/profiles') } catch { /* store exposes safe message */ }
}
</script>
<template>
  <main class="auth-page"><section class="auth-panel"><p class="eyebrow">Resume Matching</p><h1>Create account</h1><p class="muted">Set up your workspace and model access.</p>
    <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
      <el-form-item label="Username" prop="username"><input v-model="form.username" autocomplete="username" /></el-form-item>
      <el-form-item label="Email" prop="email"><input v-model="form.email" type="email" autocomplete="email" /></el-form-item>
      <el-form-item label="Password" prop="password"><input v-model="form.password" type="password" autocomplete="new-password" /></el-form-item>
      <fieldset><legend>Account role</legend><label><input type="radio" value="USER" v-model="form.role" data-test="role-user" @change="adminSelected = false" /> User</label><label><input type="checkbox" v-model="adminSelected" data-test="role-admin" /> Administrator</label><p class="notice">Administrators can process recovery records across owners. This is a global recovery scope, not a demo-only mode; use it only where that access is intended.</p></fieldset>
      <p v-if="validationMessage" class="error">{{ validationMessage }}</p><p v-if="auth.error && submitted" class="error">{{ auth.error }}</p><button data-test="register-submit" type="submit" @click.prevent="submit" :disabled="auth.loading">Create account</button>
    </el-form><RouterLink to="/login">Already have an account? Sign in</RouterLink>
  </section></main>
</template>
