<script setup lang="ts">
import { reactive, ref } from 'vue'; import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'; import { useRouter } from 'vue-router'; import { useAuthStore } from '../stores/auth'
const router = useRouter(); const auth = useAuthStore(); const submitted = ref(false); const formRef = ref<FormInstance>(); const validationMessage = ref(''); const form = reactive({ identifier: '', password: '' })
const rules: FormRules = { identifier: [{ required: true, message: '用户名或邮箱不能为空', trigger: 'blur' }, { max: 254, message: '用户名或邮箱长度不能超过 254 个字符', trigger: 'blur' }], password: [{ required: true, message: '密码不能为空', trigger: 'blur' }, { max: 128, message: '密码长度不能超过 128 个字符', trigger: 'blur' }] }
async function submit() {
  submitted.value = true
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (!form.identifier) validationMessage.value = '用户名或邮箱不能为空'
  else if (form.identifier.length > 254) validationMessage.value = '用户名或邮箱长度不能超过 254 个字符'
  else if (!form.password) validationMessage.value = '密码不能为空'
  else if (form.password.length > 128) validationMessage.value = '密码长度不能超过 128 个字符'
  if (validationMessage.value) return
  try { await auth.login({ ...form }); await router.push('/profiles') } catch { /* safe store error */ }
}
</script>
<template><main class="auth-page"><section class="auth-panel"><p class="eyebrow">简历匹配平台</p><h1>欢迎回来</h1><el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit"><el-form-item label="用户名或邮箱" prop="identifier"><input v-model="form.identifier" autocomplete="username" /></el-form-item><el-form-item label="密码" prop="password"><input v-model="form.password" type="password" autocomplete="current-password" /></el-form-item><p v-if="validationMessage" class="error">{{ validationMessage }}</p><p v-if="auth.error && submitted" class="error">{{ auth.error }}</p><button type="submit" @click.prevent="submit" :disabled="auth.loading">登录</button></el-form><RouterLink to="/register">创建账号</RouterLink></section></main></template>
