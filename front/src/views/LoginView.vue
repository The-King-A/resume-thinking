<script setup lang="ts">
import { reactive, ref } from 'vue'; import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'; import { useRouter } from 'vue-router'; import { useAuthStore } from '../stores/auth'
const router = useRouter(); const auth = useAuthStore(); const submitted = ref(false); const formRef = ref<FormInstance>(); const validationMessage = ref(''); const form = reactive({ identifier: '', password: '' })
const rules: FormRules = { identifier: [{ required: true, message: 'Identifier is required', trigger: 'blur' }, { max: 254, message: 'Identifier must be at most 254 characters', trigger: 'blur' }], password: [{ required: true, message: 'Password is required', trigger: 'blur' }, { max: 128, message: 'Password must be at most 128 characters', trigger: 'blur' }] }
async function submit() {
  submitted.value = true
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (!form.identifier) validationMessage.value = 'Identifier is required'
  else if (form.identifier.length > 254) validationMessage.value = 'Identifier must be at most 254 characters'
  else if (!form.password) validationMessage.value = 'Password is required'
  else if (form.password.length > 128) validationMessage.value = 'Password must be at most 128 characters'
  if (validationMessage.value) return
  try { await auth.login({ ...form }); await router.push('/profiles') } catch { /* safe store error */ }
}
</script>
<template><main class="auth-page"><section class="auth-panel"><p class="eyebrow">Resume Matching</p><h1>Welcome back</h1><el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit"><el-form-item label="Username or email" prop="identifier"><input v-model="form.identifier" autocomplete="username" /></el-form-item><el-form-item label="Password" prop="password"><input v-model="form.password" type="password" autocomplete="current-password" /></el-form-item><p v-if="validationMessage" class="error">{{ validationMessage }}</p><p v-if="auth.error && submitted" class="error">{{ auth.error }}</p><button type="submit" @click.prevent="submit" :disabled="auth.loading">Sign in</button></el-form><RouterLink to="/register">Create an account</RouterLink></section></main></template>
