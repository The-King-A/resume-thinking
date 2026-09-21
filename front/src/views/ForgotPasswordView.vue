<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref<FormInstance>()
const validationMessage = ref('')
const form = reactive({ identifier: '', newPassword: '', confirmPassword: '' })
const rules: FormRules = {
  identifier: [{ required: true, message: '用户名或邮箱不能为空', trigger: 'blur' }, { max: 254, message: '用户名或邮箱长度不能超过 254 个字符', trigger: 'blur' }],
  newPassword: [{ required: true, message: '新密码不能为空', trigger: 'blur' }, { min: 12, message: '密码至少需要 12 个字符', trigger: 'blur' }, { max: 128, message: '密码长度不能超过 128 个字符', trigger: 'blur' }],
  confirmPassword: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }],
}

async function submit() {
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (!form.identifier) validationMessage.value = '用户名或邮箱不能为空'
  else if (form.identifier.length > 254) validationMessage.value = '用户名或邮箱长度不能超过 254 个字符'
  else if (form.newPassword.length < 12) validationMessage.value = '密码至少需要 12 个字符'
  else if (form.newPassword.length > 128) validationMessage.value = '密码长度不能超过 128 个字符'
  else if (form.newPassword !== form.confirmPassword) validationMessage.value = '两次输入的密码不一致'
  if (validationMessage.value) return
  try {
    await auth.resetPassword({ identifier: form.identifier, newPassword: form.newPassword })
    await router.push('/login')
  } catch { /* store exposes a safe error message */ }
}
</script>

<template>
  <main class="auth-page"><section class="auth-panel"><p class="eyebrow">简历匹配平台</p><h1>重置密码</h1><p class="muted">仅限本地开发环境的已注册账号。</p>
    <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
      <el-form-item label="用户名或邮箱" prop="identifier"><input v-model="form.identifier" autocomplete="username" /></el-form-item>
      <el-form-item label="新密码" prop="newPassword"><input v-model="form.newPassword" type="password" autocomplete="new-password" /></el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword"><input v-model="form.confirmPassword" type="password" autocomplete="new-password-confirmation" /></el-form-item>
      <p v-if="validationMessage" class="error">{{ validationMessage }}</p><button type="submit" @click.prevent="submit" :disabled="auth.loading">重置密码</button>
    </el-form><RouterLink to="/login">返回登录</RouterLink>
  </section></main>
</template>
