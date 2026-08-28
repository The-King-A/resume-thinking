<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import type { UserRole } from '../api/contracts'
const router = useRouter(); const auth = useAuthStore(); const submitted = ref(false)
const form = reactive({ username: '', email: '', password: '', role: 'USER' as UserRole })
const adminSelected = ref(false); const formRef = ref<FormInstance>(); const validationMessage = ref(''); const rules: FormRules = { username: [{ required: true, message: '用户名不能为空', trigger: 'blur' }, { min: 3, message: '用户名至少需要 3 个字符', trigger: 'blur' }, { max: 64, message: '用户名长度不能超过 64 个字符', trigger: 'blur' }, { pattern: /^[A-Za-z0-9._-]+$/, message: '用户名只能包含字母、数字、点、下划线或连字符', trigger: 'blur' }], email: [{ required: true, message: '邮箱不能为空', trigger: 'blur' }, { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }, { max: 254, message: '邮箱长度不能超过 254 个字符', trigger: 'blur' }], password: [{ required: true, message: '密码不能为空', trigger: 'blur' }, { min: 12, message: '密码至少需要 12 个字符', trigger: 'blur' }, { max: 128, message: '密码长度不能超过 128 个字符', trigger: 'blur' }] }
async function submit() {
  submitted.value = true
  form.role = adminSelected.value ? 'ADMIN' : 'USER'
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (form.username.length < 3) validationMessage.value = '用户名至少需要 3 个字符'
  else if (form.username.length > 64) validationMessage.value = '用户名长度不能超过 64 个字符'
  else if (!/^[A-Za-z0-9._-]+$/.test(form.username)) validationMessage.value = '用户名只能包含字母、数字、点、下划线或连字符'
  else if (!form.email) validationMessage.value = '邮箱不能为空'
  else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) validationMessage.value = '请输入有效的邮箱地址'
  else if (form.email.length > 254) validationMessage.value = '邮箱长度不能超过 254 个字符'
  else if (form.password.length < 12) validationMessage.value = '密码至少需要 12 个字符'
  else if (form.password.length > 128) validationMessage.value = '密码长度不能超过 128 个字符'
  if (form.username.length === 0) validationMessage.value = '用户名不能为空'
  if (validationMessage.value) return
  try { await auth.register({ ...form }); await router.push('/profiles') } catch { /* store exposes safe message */ }
}
</script>
<template>
  <main class="auth-page"><section class="auth-panel"><p class="eyebrow">简历匹配平台</p><h1>创建账号</h1><p class="muted">设置你的工作区和模型访问权限。</p>
    <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
      <el-form-item label="用户名" prop="username"><input v-model="form.username" autocomplete="username" /></el-form-item>
      <el-form-item label="邮箱" prop="email"><input v-model="form.email" type="email" autocomplete="email" /></el-form-item>
      <el-form-item label="密码" prop="password"><input v-model="form.password" type="password" autocomplete="new-password" /></el-form-item>
      <fieldset><legend>账号角色</legend><label><input type="radio" value="USER" v-model="form.role" data-test="role-user" @change="adminSelected = false" /> 普通用户</label><label><input type="checkbox" v-model="adminSelected" data-test="role-admin" /> 管理员</label><p class="notice">管理员可以处理跨用户的恢复记录。这是全局恢复权限，并非仅用于演示；请仅在确实需要该权限时选择。</p></fieldset>
      <p v-if="validationMessage" class="error">{{ validationMessage }}</p><p v-if="auth.error && submitted" class="error">{{ auth.error }}</p><button data-test="register-submit" type="submit" @click.prevent="submit" :disabled="auth.loading">创建账号</button>
    </el-form><RouterLink to="/login">已有账号？去登录</RouterLink>
  </section></main>
</template>
