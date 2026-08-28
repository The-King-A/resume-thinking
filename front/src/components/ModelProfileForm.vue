<script setup lang="ts">
import { reactive, ref, watch } from 'vue'; import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'; import type { LlmProfile, CreateLlmProfileRequest, UpdateLlmProfileRequest } from '../api/contracts'
type ProfileFormPayload = CreateLlmProfileRequest | UpdateLlmProfileRequest
const props = defineProps<{ profile?: LlmProfile | null }>(); const emit = defineEmits<{ save: [payload: ProfileFormPayload]; test: [payload: ProfileFormPayload] }>(); const testing = ref(false); const modelOptions = ref<string[]>([]); const formRef = ref<FormInstance>(); const validationMessage = ref('')
const form = reactive<CreateLlmProfileRequest>({ displayName: '', endpointUrl: 'https://api.openai.com/v1', modelName: '', apiKey: '', selected: false })
watch(() => props.profile, (profile) => { if (profile) { form.displayName = profile.displayName; form.endpointUrl = profile.endpointUrl; form.modelName = profile.modelName; form.selected = profile.selected; form.apiKey = '' } }, { immediate: true })
const presets = [{ label: 'OpenAI', url: 'https://api.openai.com/v1' }, { label: 'Azure OpenAI', url: 'https://your-resource.openai.azure.com' }, { label: 'Custom', url: '' }]
const rules: FormRules = { displayName: [{ required: true, message: '请输入配置名称', trigger: 'blur' }, { max: 100, message: '配置名称不能超过100个字符', trigger: 'blur' }], endpointUrl: [{ required: true, message: '请输入接口地址', trigger: 'blur' }, { type: 'url', message: '请输入有效的接口地址', trigger: 'blur' }, { max: 2048, message: '接口地址不能超过2048个字符', trigger: 'blur' }], modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }, { max: 200, message: '模型名称不能超过200个字符', trigger: 'blur' }], apiKey: [{ max: 4096, message: 'API密钥不能超过4096个字符', trigger: 'blur' }] }
async function validateForm(requireApiKey: boolean) {
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (form.displayName.length < 1) validationMessage.value = '请输入配置名称'
  else if (form.displayName.length > 100) validationMessage.value = '配置名称不能超过100个字符'
  else if (!form.endpointUrl) validationMessage.value = '请输入接口地址'
  else if (form.endpointUrl.length > 2048) validationMessage.value = '接口地址不能超过2048个字符'
  else { try { new URL(form.endpointUrl) } catch { validationMessage.value = '请输入有效的接口地址' } }
  if (!validationMessage.value && form.modelName.length < 1) validationMessage.value = '请输入模型名称'
  else if (!validationMessage.value && form.modelName.length > 200) validationMessage.value = '模型名称不能超过200个字符'
  if (!validationMessage.value && requireApiKey && form.apiKey.length < 1) validationMessage.value = '请输入API密钥'
  else if (!validationMessage.value && form.apiKey.length > 4096) validationMessage.value = 'API密钥不能超过4096个字符'
  return !validationMessage.value
}
function payload(): ProfileFormPayload { const value: Record<string, unknown> = { ...form }; if (props.profile && !form.apiKey) delete value.apiKey; return value as unknown as ProfileFormPayload }
async function save() { if (!(await validateForm(!props.profile?.hasApiKey))) return; emit('save', payload()) }
async function test() { if (!(await validateForm(false))) return; emit('test', payload()) }
function choose(url: string) { form.endpointUrl = url }
function setModels(models: string[]) { modelOptions.value = models }
function clearApiKey() { form.apiKey = '' }
defineExpose({ setModels, setTesting: (value: boolean) => { testing.value = value }, clearApiKey })
</script>
<template><el-form ref="formRef" class="profile-form" :model="form" :rules="rules" @submit.prevent="save"><el-form-item label="配置名称" prop="displayName"><input v-model="form.displayName" /></el-form-item><el-form-item label="服务商预设"><select @change="choose(($event.target as HTMLSelectElement).value)"><option v-for="preset in presets" :key="preset.label" :value="preset.url">{{ preset.label === 'Custom' ? '自定义' : preset.label }}</option></select></el-form-item><el-form-item label="接口地址" prop="endpointUrl"><input v-model="form.endpointUrl" type="url" /></el-form-item><el-form-item label="模型" prop="modelName"><select v-if="modelOptions.length" v-model="form.modelName"><option v-for="model in modelOptions" :key="model">{{ model }}</option></select><input v-else v-model="form.modelName" placeholder="例如 gpt-4o-mini" /></el-form-item><el-form-item label="API密钥" prop="apiKey"><span class="muted">（仅写入）</span><input v-model="form.apiKey" type="password" autocomplete="new-password" :placeholder="props.profile?.hasApiKey ? '输入新密钥以替换已保存的密钥' : '输入服务商密钥'" /></el-form-item><label class="check"><input v-model="form.selected" type="checkbox" /> 设为默认配置</label><p v-if="validationMessage" class="error">{{ validationMessage }}</p><div class="form-actions"><button type="button" @click="test" :disabled="testing">测试连接</button><button type="submit" @click.prevent="save">{{ props.profile ? '保存修改' : '添加配置' }}</button></div></el-form></template>
