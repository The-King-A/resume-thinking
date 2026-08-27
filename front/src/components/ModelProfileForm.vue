<script setup lang="ts">
import { reactive, ref, watch } from 'vue'; import { ElForm, ElFormItem, type FormInstance, type FormRules } from 'element-plus'; import type { LlmProfile, CreateLlmProfileRequest, UpdateLlmProfileRequest } from '../api/contracts'
type ProfileFormPayload = CreateLlmProfileRequest | UpdateLlmProfileRequest
const props = defineProps<{ profile?: LlmProfile | null }>(); const emit = defineEmits<{ save: [payload: ProfileFormPayload]; test: [payload: ProfileFormPayload] }>(); const testing = ref(false); const modelOptions = ref<string[]>([]); const formRef = ref<FormInstance>(); const validationMessage = ref('')
const form = reactive<CreateLlmProfileRequest>({ displayName: '', endpointUrl: 'https://api.openai.com/v1', modelName: '', apiKey: '', selected: false })
watch(() => props.profile, (profile) => { if (profile) { form.displayName = profile.displayName; form.endpointUrl = profile.endpointUrl; form.modelName = profile.modelName; form.selected = profile.selected; form.apiKey = '' } }, { immediate: true })
const presets = [{ label: 'OpenAI', url: 'https://api.openai.com/v1' }, { label: 'Azure OpenAI', url: 'https://your-resource.openai.azure.com' }, { label: 'Custom', url: '' }]
const rules: FormRules = { displayName: [{ required: true, message: 'Profile name is required', trigger: 'blur' }, { max: 100, message: 'Profile name must be at most 100 characters', trigger: 'blur' }], endpointUrl: [{ required: true, message: 'Endpoint URL is required', trigger: 'blur' }, { type: 'url', message: 'Enter a valid endpoint URL', trigger: 'blur' }, { max: 2048, message: 'Endpoint URL must be at most 2048 characters', trigger: 'blur' }], modelName: [{ required: true, message: 'Model is required', trigger: 'blur' }, { max: 200, message: 'Model must be at most 200 characters', trigger: 'blur' }], apiKey: [{ max: 4096, message: 'API key must be at most 4096 characters', trigger: 'blur' }] }
async function validateForm(requireApiKey: boolean) {
  validationMessage.value = ''
  await formRef.value?.validate().catch(() => false)
  if (form.displayName.length < 1) validationMessage.value = 'Profile name is required'
  else if (form.displayName.length > 100) validationMessage.value = 'Profile name must be at most 100 characters'
  else if (!form.endpointUrl) validationMessage.value = 'Endpoint URL is required'
  else if (form.endpointUrl.length > 2048) validationMessage.value = 'Endpoint URL must be at most 2048 characters'
  else { try { new URL(form.endpointUrl) } catch { validationMessage.value = 'Enter a valid endpoint URL' } }
  if (!validationMessage.value && form.modelName.length < 1) validationMessage.value = 'Model is required'
  else if (!validationMessage.value && form.modelName.length > 200) validationMessage.value = 'Model must be at most 200 characters'
  if (!validationMessage.value && requireApiKey && form.apiKey.length < 1) validationMessage.value = 'API key is required'
  else if (!validationMessage.value && form.apiKey.length > 4096) validationMessage.value = 'API key must be at most 4096 characters'
  return !validationMessage.value
}
function payload(): ProfileFormPayload { const value: Record<string, unknown> = { ...form }; if (props.profile && !form.apiKey) delete value.apiKey; return value as ProfileFormPayload }
async function save() { if (!(await validateForm(!props.profile?.hasApiKey))) return; emit('save', payload()) }
async function test() { if (!(await validateForm(false))) return; emit('test', payload()) }
function choose(url: string) { form.endpointUrl = url }
function setModels(models: string[]) { modelOptions.value = models }
function clearApiKey() { form.apiKey = '' }
defineExpose({ setModels, setTesting: (value: boolean) => { testing.value = value }, clearApiKey })
</script>
<template><el-form ref="formRef" class="profile-form" :model="form" :rules="rules" @submit.prevent="save"><el-form-item label="Profile name" prop="displayName"><input v-model="form.displayName" /></el-form-item><el-form-item label="Provider preset"><select @change="choose(($event.target as HTMLSelectElement).value)"><option v-for="preset in presets" :key="preset.label" :value="preset.url">{{ preset.label }}</option></select></el-form-item><el-form-item label="Endpoint URL" prop="endpointUrl"><input v-model="form.endpointUrl" type="url" /></el-form-item><el-form-item label="Model" prop="modelName"><select v-if="modelOptions.length" v-model="form.modelName"><option v-for="model in modelOptions" :key="model">{{ model }}</option></select><input v-else v-model="form.modelName" placeholder="e.g. gpt-4o-mini" /></el-form-item><el-form-item label="API key" prop="apiKey"><span class="muted">(write-only)</span><input v-model="form.apiKey" type="password" autocomplete="new-password" :placeholder="props.profile?.hasApiKey ? 'Enter a new key to replace the stored key' : 'Enter provider key'" /></el-form-item><label class="check"><input v-model="form.selected" type="checkbox" /> Use as default profile</label><p v-if="validationMessage" class="error">{{ validationMessage }}</p><div class="form-actions"><button type="button" @click="test" :disabled="testing">Test connection</button><button type="submit" @click.prevent="save">{{ props.profile ? 'Save changes' : 'Add profile' }}</button></div></el-form></template>
