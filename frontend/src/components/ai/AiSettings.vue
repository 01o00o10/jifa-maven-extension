<script setup lang="ts">
import axios from 'axios';
import { useRouter } from 'vue-router';

const router = useRouter();
const loading = ref(true);
const saving = ref(false);
const saved = ref(false);
const tested = ref(false);
const testing = ref(false);
const error = ref('');
const configLoaded = ref(false);
const apiKeyField = ref<any>();
const form = reactive({
  enabled: false,
  provider: 'deepseek',
  model: 'deepseek-chat',
  baseUrl: 'https://api.deepseek.com',
  mcpUrl: '',
  apiKey: '',
  apiKeyConfigured: false,
  mcpEnabled: false,
  configurationSource: 'application.yml'
});

const modelOptions = ['deepseek-chat', 'deepseek-flash', 'deepseek-v4-flash', 'qwen-plus', 'qwen-max'];

onMounted(async () => {
  try {
    const response = await axios.get('/jifa-api/ai/config');
    Object.assign(form, response.data);
    configLoaded.value = true;
  } catch (e: any) {
    error.value = e?.response?.data?.message || `读取 AI 配置失败（HTTP ${e?.response?.status || 'unknown'}）`;
  } finally {
    loading.value = false;
  }
});

async function save(): Promise<boolean> {
  saving.value = true;
  saved.value = false;
  try {
    // Some password managers paint a value into the native input without
    // triggering Vue's v-model event. Read it once before submitting.
    const visibleApiKey = form.apiKey || apiKeyField.value?.input?.value || '';
    const response = await axios.post('/jifa-api/ai/config', {
      enabled: form.enabled,
      provider: form.provider,
      model: form.model,
      baseUrl: form.baseUrl,
      mcpUrl: form.mcpUrl,
      apiKey: visibleApiKey
    });
    Object.assign(form, response.data);
    if (visibleApiKey && !response.data?.apiKeyConfigured) {
      throw new Error('API Key 未被服务端保存，请重新输入后再试');
    }
    saved.value = true;
    tested.value = false;
    error.value = '';
    form.apiKey = '';
    return true;
  } catch (e: any) {
    error.value = e?.response?.data?.message || e.message || `保存 AI 配置失败（HTTP ${e?.response?.status || 'unknown'}）`;
    return false;
  } finally {
    saving.value = false;
  }
}

async function testConnection() {
  testing.value = true;
  tested.value = false;
  error.value = '';
  try {
    if (!await save()) return;
    const response = await axios.post('/jifa-api/ai/config/test');
    if (!response.data?.success) throw new Error(response.data?.message || 'AI 连接测试失败');
    tested.value = true;
  } catch (e: any) {
    error.value = e?.response?.data?.message || e.message || 'AI 连接测试失败';
  } finally {
    testing.value = false;
  }
}

function goBack() {
  if (window.history.length > 1) router.back();
  else router.push('/');
}
</script>

<template>
  <div class="ai-settings">
    <el-card v-loading="loading">
      <template #header>
        <div class="settings-header">
          <span>AI 诊断配置</span>
          <el-tag v-if="configLoaded" type="success">配置接口可用</el-tag>
        </div>
      </template>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-alert v-if="configLoaded && !form.enabled" type="warning" :closable="false" title="AI 当前未启用" />
      <el-alert v-if="configLoaded && form.enabled && !form.apiKeyConfigured && !form.apiKey" type="warning" :closable="false" title="当前没有检测到 API Key，请填写后保存" />
      <el-alert v-if="saved" type="success" :closable="false" :title="`AI 配置已保存，当前模型：${form.model}`" />
      <el-alert v-if="tested" type="success" :closable="false" title="模型连接测试成功，AI 已可以处理请求" />
      <div v-if="configLoaded" class="config-status">
        <el-tag :type="form.apiKeyConfigured ? 'success' : 'danger'">API Key：{{ form.apiKeyConfigured ? '已配置' : '未配置' }}</el-tag>
        <el-tag :type="form.mcpEnabled ? 'success' : 'info'">MCP：{{ form.mcpEnabled ? '已启用' : '内置模式' }}</el-tag>
        <el-tag type="info">当前模型：{{ form.model }}</el-tag>
        <el-tag :type="form.configurationSource === 'runtime' ? 'success' : 'info'">来源：{{ form.configurationSource === 'runtime' ? '页面内存配置' : 'application.yml' }}</el-tag>
      </div>
      <el-form label-width="130px" @submit.prevent="save">
        <el-form-item label="启用 AI"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="Provider"><el-input v-model="form.provider" /></el-form-item>
        <el-form-item label="模型">
          <el-select v-model="form.model" filterable allow-create @change="saved = false; tested = false">
            <el-option v-for="model in modelOptions" :key="model" :label="model" :value="model" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL"><el-input v-model="form.baseUrl" /></el-form-item>
        <el-form-item label="MCP URL"><el-input v-model="form.mcpUrl" placeholder="可选，例如 http://127.0.0.1:18081/mcp" /></el-form-item>
        <el-form-item label="API Key"><el-input ref="apiKeyField" v-model="form.apiKey" type="password" show-password autocomplete="new-password" placeholder="留空表示保持当前 Key" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="testing" :disabled="!form.enabled" @click="testConnection">测试连接</el-button>
          <el-button @click="goBack">返回</el-button>
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" title="配置只修改当前运行实例内存；重启后请通过 application.yml 或环境变量配置。" />
    </el-card>
  </div>
</template>

<style scoped>
.ai-settings { max-width: 760px; margin: 32px auto; padding: 0 16px; }
.el-select, .el-input { width: 100%; }
.el-text { margin-left: 12px; }
.settings-header, .config-status { display: flex; align-items: center; gap: 8px; }
.config-status { margin: 12px 0 18px; flex-wrap: wrap; }
</style>
