<script setup lang="ts">
import { reactive, ref } from 'vue'; import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'; import { useRoute, useRouter } from 'vue-router'; import { useAuthStore } from '../stores/auth'
const route = useRoute(); const router = useRouter(); const auth = useAuthStore(); const formRef = ref<FormInstance>(); const validationMessage = ref(''); const form = reactive({ identifier: '', password: '' })
const rules: FormRules = { identifier: [{ required: true, message: '用户名或邮箱不能为空', trigger: 'blur' }, { max: 254, message: '用户名或邮箱长度不能超过 254 个字符', trigger: 'blur' }], password: [{ required: true, message: '密码不能为空', trigger: 'blur' }, { max: 128, message: '密码长度不能超过 128 个字符', trigger: 'blur' }] }
function authRedirect() {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')
    || redirect === '/login' || redirect.startsWith('/login?') || redirect.startsWith('/route-unavailable')) return '/profiles'
  return redirect
}
async function goToProfiles() {
  const target = authRedirect()
  try {
    const navigationFailure = await router.replace(target)
    if (!navigationFailure) return
  } catch { /* Route-level recovery below preserves the authenticated session. */ }
  await router.replace({ name: 'route-unavailable', query: { retry: target } }).catch(() => undefined)
}
async function submit() {
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (!form.identifier) validationMessage.value = '用户名或邮箱不能为空'
  else if (form.identifier.length > 254) validationMessage.value = '用户名或邮箱长度不能超过 254 个字符'
  else if (!form.password) validationMessage.value = '密码不能为空'
  else if (form.password.length > 128) validationMessage.value = '密码长度不能超过 128 个字符'
  if (validationMessage.value) return
  const identifier = form.identifier.trim().includes('@') ? form.identifier.trim().toLowerCase() : form.identifier.trim()
  try { await auth.login({ identifier, password: form.password }) } catch { return }
  await goToProfiles()
}
</script>
<template>
  <main class="auth-page auth-page-branded">
    <section class="auth-showcase" aria-label="产品介绍">
      <div class="showcase-copy">
        <p class="auth-brand-name" data-test="brand-name">ai-resume-thinking</p>
        <p class="auth-product-name">简历驱动岗位匹配与模拟面试平台</p>
        <p class="eyebrow">可信求职辅助</p>
        <h1>让每一份经历，<br /><em>都有证据可循。</em></h1>
        <p>从简历事实出发，理解岗位要求，找到真正适合你的下一步。</p>
      </div>
      <div class="evidence-visual" aria-hidden="true">
        <div class="orbit orbit-one"></div><div class="orbit orbit-two"></div>
        <div class="node node-resume"><span class="node-icon">CV</span><b>我的简历</b><small>事实档案</small></div>
        <div class="node node-match"><span class="node-icon">AI</span><b>岗位匹配</b><small>证据链分析</small></div>
        <div class="node node-interview"><span class="node-icon">GO</span><b>面试准备</b><small>从缺口出发</small></div>
        <div class="signal-line line-one"></div><div class="signal-line line-two"></div><div class="signal-line line-three"></div>
      </div>
      <div class="trust-strip"><span><i></i> 原文证据</span><span><i></i> 脱敏优先</span><span><i></i> 事实约束</span></div>
    </section>
    <section class="auth-panel">
      <div class="auth-heading"><p class="eyebrow">欢迎回来</p><h2>进入你的求职工作区</h2><p class="muted">继续查看简历证据与岗位匹配结果。</p></div>
      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
        <el-form-item label="用户名或邮箱" prop="identifier"><input v-model="form.identifier" autocomplete="username" /></el-form-item>
        <el-form-item label="密码" prop="password"><input v-model="form.password" type="password" autocomplete="current-password" /></el-form-item>
        <p v-if="validationMessage && validationMessage !== '用户名或邮箱不能为空' && validationMessage !== '密码不能为空'" class="error">{{ validationMessage }}</p>
        <div class="login-flow" data-test="login-flow" aria-label="求职分析路径">
          <div class="login-flow-heading">
            <strong>分析从这里开始</strong>
            <small>把经历转成可行动的求职准备</small>
          </div>
          <div class="login-flow-steps">
            <span class="login-flow-step"><i class="login-flow-dot" aria-hidden="true"></i><b>简历事实</b></span>
            <i class="login-flow-connector" aria-hidden="true"></i>
            <span class="login-flow-step"><i class="login-flow-dot" aria-hidden="true"></i><b>岗位要求</b></span>
            <i class="login-flow-connector" aria-hidden="true"></i>
            <span class="login-flow-step"><i class="login-flow-dot" aria-hidden="true"></i><b>面试演练</b></span>
          </div>
        </div>
        <button class="primary-button" type="submit" @click.prevent="submit" :disabled="auth.loading"><span>{{ auth.loading ? '正在进入...' : '登录工作区' }}</span><b aria-hidden="true">&gt;</b></button>
      </el-form>
      <nav class="auth-links auth-footer" aria-label="账号操作"><span>还没有账号？</span><RouterLink to="/register">创建账号</RouterLink><RouterLink to="/forgot-password">忘记密码</RouterLink></nav>
      <p class="privacy-note"><span class="privacy-dot" aria-hidden="true"></span> 你的简历数据仅用于已授权的分析流程</p>
    </section>
  </main>
</template>
