<script setup lang="ts">
import axios from 'axios';
import { useAiStore, type AiFileContext } from '@/stores/ai';
import { useAnalysisStore } from '@/stores/analysis';
import { useRouter } from 'vue-router';

const ai = useAiStore();
const router = useRouter();
const analysis = useAnalysisStore();
const draft = ref('');
const files = ref<AiFileContext[]>([]);
const selected = ref('');
const loadingFiles = ref(false);
const fileError = ref('');
const maximized = ref(false);
const messageArea = ref<HTMLElement>();

watch(() => ai.messages.length, () => {
  nextTick(() => {
    if (messageArea.value) messageArea.value.scrollTop = messageArea.value.scrollHeight;
  });
});

const typeLabels: Record<string, string> = {
  HEAP_DUMP: 'Heap Dump',
  GC_LOG: 'GC 日志',
  THREAD_DUMP: 'Thread Dump',
  JFR_FILE: 'JFR'
};

const quickQuestions = computed(() => {
  switch (ai.context?.type) {
    case 'GC_LOG': return ['分析 GC 是否频繁', '找出最长停顿', '判断是否存在内存压力'];
    case 'THREAD_DUMP': return ['检查是否存在死锁', '找出阻塞线程', '分析线程池问题'];
    case 'HEAP_DUMP': return ['分析疑似内存泄漏', '查看占用内存最多的对象', '分析最大引用链'];
    case 'JFR_FILE': return ['分析 CPU 消耗最高的方法', '查找内存分配热点', '分析锁竞争'];
    default: return [];
  }
});

function assistantReplyNumber(messageIndex: number): number {
  return ai.messages.slice(0, messageIndex + 1).filter((message) => message.role === 'assistant').length;
}

onMounted(() => {
  ai.hydrate();
  selected.value = ai.context?.uniqueName || '';
  loadFiles();
  if (analysis.target && analysis.fileType) {
    axios.get(`/jifa-api/files/${analysis.target}`).then((response) => {
      setContext(response.data);
    }).catch(() => undefined);
  }
});

function setContext(file: any) {
  const context: AiFileContext = {
    uniqueName: file.uniqueName,
    originalName: file.originalName,
    type: file.type,
    size: file.size
  };
  ai.setContext(context);
  selected.value = context.uniqueName;
}

async function loadFiles() {
  loadingFiles.value = true;
  fileError.value = '';
  try {
    try {
      const response = await axios.get('/jifa-api/files', { params: { page: 1, pageSize: 500 } });
      const data = Array.isArray(response.data?.data) ? response.data.data : [];
      files.value = data.filter((file: any) => typeLabels[file.type]);
      if (files.value.length === 1 && !ai.context) setContext(files.value[0]);
      return;
    } catch (e: any) {
      fileError.value = e?.response?.data?.message || '读取文件列表失败';
      // Fall back to per-type requests for older server deployments.
    }
    const result: AiFileContext[] = [];
    await Promise.all(Object.keys(typeLabels).map(async (type) => {
      try {
        const response = await axios.get('/jifa-api/files', { params: { type, page: 1, pageSize: 500 } });
        result.push(...(response.data?.data || []));
      } catch (_) {
        // Keep the selector usable when one file type is unavailable.
      }
    }));
    files.value = result;
    if (files.value.length === 1 && !ai.context) setContext(files.value[0]);
    if (result.length) fileError.value = '';
  } finally {
    loadingFiles.value = false;
  }
}

function selectFile(uniqueName: string) {
  const file = files.value.find((item) => item.uniqueName === uniqueName);
  if (file) setContext(file);
}

function open() {
  ai.opened = true;
  loadFiles();
}

function close() {
  ai.opened = false;
}

function openSettings() {
  router.push('/ai-settings');
  ai.opened = false;
}

async function clearConversation() {
  if (ai.sessionId) {
    await fetch('/jifa-api/ai/session/clear', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        sessionId: ai.sessionId,
        target: ai.context?.uniqueName,
        fileType: ai.context?.type
      })
    }).catch(() => undefined);
  }
  ai.clear();
}

function submitQuickQuestion(question: string) {
  draft.value = question;
  submit();
}

async function submit() {
  const question = draft.value.trim();
  if (!question || !ai.context || ai.busy) return;

  ai.addMessage({ role: 'user', content: question });
  draft.value = '';
  ai.busy = true;
  ai.status = 'thinking';

  try {
    const response = await fetch('/jifa-api/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({
        target: ai.context.uniqueName,
        fileType: ai.context.type,
        message: question,
        sessionId: ai.sessionId
      })
    });
    if (!response.ok || !response.body) throw new Error(`AI 请求失败: ${response.status}`);

    ai.status = 'analyzing';
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let answer = '';
    let streamed = false;
    const consumeEvent = (name: string, data: string) => {
      handleSseEvent(name, data, (value, eventName) => {
        if (eventName === 'delta') {
          streamed = true;
          answer += value;
        } else if (eventName === 'message' && !streamed) {
          // Non-streaming providers return the answer in one message event.
          answer = value;
        }
      });
    };
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        buffer += decoder.decode();
        processSseBuffer(buffer, consumeEvent);
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      buffer = processSseBuffer(buffer, consumeEvent);
    }
    ai.addMessage({ role: 'assistant', content: answer || 'AI 没有返回内容。' });
  } catch (error: any) {
    ai.status = 'error';
    ai.addMessage({ role: 'assistant', content: error.message || 'AI 请求失败。' });
  } finally {
    ai.busy = false;
    ai.progress = '';
    if (ai.status !== 'error') ai.status = 'idle';
  }
}

function parseSseData(data: string): string {
  try {
    const parsed = JSON.parse(data);
    return typeof parsed === 'string' ? parsed : data;
  } catch (_) {
    return data;
  }
}

function processSseBuffer(input: string, handler: (name: string, data: string) => void): string {
  const normalized = input.replace(/\r\n/g, '\n');
  const events = normalized.split('\n\n');
  const remainder = events.pop() || '';
  for (const event of events) {
    const data = event.split('\n').filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim()).join('\n');
    const name = event.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim() || '';
    if (data) handler(name, data);
  }
  return remainder;
}

function handleSseEvent(name: string, rawData: string, consume: (value: string, eventName: string) => void) {
  const value = parseSseData(rawData);
  if (name === 'error') throw new Error(value);
  if (name === 'session') ai.setSessionId(value);
  if (name === 'status') {
    ai.progress = value;
    ai.progressEvents.push(value);
  }
  if (name === 'delta') {
    consume(value, name);
    ai.status = 'streaming';
  }
  if (name === 'message') {
    // Non-streaming providers send one message event. Streaming providers already sent delta events.
    consume(value, name);
    ai.status = 'streaming';
  }
}
</script>

<template>
  <button v-if="!ai.opened" class="ai-float" title="AI 诊断助手" @click="open">
    <span class="ai-face">AI</span>
  </button>

  <el-card v-else :class="['ai-panel', { maximized }]" shadow="always">
    <template #header>
      <div class="ai-header">
        <span><span class="ai-face small">AI</span> Jifa 诊断助手</span>
        <span>
          <el-button link @click="clearConversation">清空</el-button>
          <el-button link @click="openSettings">配置</el-button>
          <el-button link @click="maximized = !maximized">{{ maximized ? '还原' : '放大' }}</el-button>
          <el-button link @click="close">收起</el-button>
        </span>
      </div>
    </template>

    <div class="ai-context">
      <div class="file-select-row">
        <el-select v-model="selected" :loading="loadingFiles" :disabled="ai.busy" popper-class="ai-file-select-popper" placeholder="请选择分析文件" @change="selectFile">
          <el-option v-for="file in files" :key="file.uniqueName" :label="`${file.originalName} (${typeLabels[file.type] || file.type})`" :value="file.uniqueName" />
        </el-select>
        <el-button link type="primary" @click="loadFiles">刷新</el-button>
      </div>
      <div v-if="files.length > 1" class="file-shortcuts">
        <el-button
          v-for="file in files"
          :key="file.uniqueName"
          :type="selected === file.uniqueName ? 'primary' : 'default'"
          :disabled="ai.busy"
          size="small"
          :title="file.originalName"
          @click="setContext(file)"
        >
          {{ file.originalName }}
        </el-button>
      </div>
      <el-text v-if="fileError" type="danger" size="small">{{ fileError }}</el-text>
      <el-text v-else-if="!loadingFiles" type="info" size="small">已加载 {{ files.length }} 个可分析文件</el-text>
      <el-text v-if="ai.context" size="small">当前：{{ ai.context.originalName }}</el-text>
      <el-text v-else type="warning" size="small">请先选择分析文件</el-text>
    </div>

    <div ref="messageArea" class="ai-messages">
      <el-empty v-if="!ai.messages.length" description="选择文件后，可以开始提问" />
      <template v-for="(message, index) in ai.messages" :key="index">
        <el-divider v-if="message.role === 'assistant'" class="ai-reply-divider" content-position="left">
          AI 回复 #{{ assistantReplyNumber(index) }}
        </el-divider>
        <div :class="['ai-message', message.role]">
          <div class="role">{{ message.role === 'user' ? '你' : 'AI' }}</div>
          <div class="content">{{ message.content }}</div>
        </div>
      </template>
    </div>
    <el-text v-if="ai.busy && ai.progress" class="ai-progress" size="small" type="info">
      {{ ai.progress }}
    </el-text>
    <div v-if="ai.progressEvents.length" class="ai-tools">
      <el-text size="small" type="info">本次调用记录</el-text>
      <div v-for="(event, index) in ai.progressEvents" :key="index" class="ai-tool-event">{{ event }}</div>
    </div>

    <div v-if="quickQuestions.length" class="quick-questions">
      <el-button v-for="question in quickQuestions" :key="question" size="small" @click="submitQuickQuestion(question)">
        {{ question }}
      </el-button>
    </div>

    <div class="ai-input">
      <el-input v-model="draft" type="textarea" :rows="2" resize="none" placeholder="请基于当前文件提问" @keydown.ctrl.enter="submit" />
      <el-button type="primary" :loading="ai.busy" :disabled="!ai.context" @click="submit">发送</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.ai-float { position: fixed; right: 26px; bottom: 26px; z-index: 3000; width: 58px; height: 58px; border: 0; border-radius: 50%; background: linear-gradient(135deg, #409eff, #7c4dff); color: #fff; box-shadow: 0 8px 24px #409eff66; cursor: pointer; }
.ai-face { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; border-radius: 50%; background: #fff; color: #5b4bdb; font-weight: 700; }
.ai-face.small { width: 25px; height: 25px; font-size: 11px; margin-right: 6px; }
.ai-panel { position: fixed; right: 16px; bottom: 16px; z-index: 3000; width: min(620px, calc(100vw - 32px)); height: min(820px, calc(100vh - 32px)); display: flex; flex-direction: column; }
.ai-panel.maximized { inset: 16px; width: auto; height: auto; }
.ai-panel :deep(.el-card__header) { flex: none; }
.ai-panel :deep(.el-card__body) { flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.ai-header { display: flex; align-items: center; justify-content: space-between; }
.ai-context { flex: none; display: flex; flex-direction: column; gap: 6px; }
.file-select-row { display: flex; gap: 6px; align-items: center; }
.file-select-row .el-select { flex: 1; }
.file-shortcuts { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 2px; }
.file-shortcuts .el-button { flex: none; max-width: 180px; overflow: hidden; text-overflow: ellipsis; }
:global(.ai-file-select-popper) { z-index: 5000 !important; }
.ai-messages { flex: 1; min-height: 220px; overflow-y: auto; overscroll-behavior: contain; margin: 12px 0; padding-right: 6px; }
.ai-message { display: flex; gap: 8px; margin: 10px 0; }
.ai-message.user { flex-direction: row-reverse; }
.ai-reply-divider { margin: 18px 0 10px; }
.ai-reply-divider :deep(.el-divider__text) { color: var(--el-color-primary); font-size: 12px; font-weight: 600; }
.role { flex: none; color: var(--el-color-primary); font-weight: 600; }
.content { max-width: 85%; padding: 8px 10px; border-radius: 8px; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light); }
.user .content { background: var(--el-color-primary-light-9); }
.quick-questions { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.ai-tools { flex: none; max-height: 110px; overflow-y: auto; margin-bottom: 8px; padding: 6px 8px; border-radius: 6px; background: var(--el-fill-color-lighter); }
.ai-tool-event { margin-top: 3px; font-size: 12px; word-break: break-all; }
.ai-input { flex: none; display: flex; gap: 8px; align-items: end; }
.ai-progress { display: block; margin: 4px 0 8px; }
@media (max-width: 700px) {
  .ai-panel, .ai-panel.maximized { inset: 8px; width: auto; height: auto; }
  .ai-messages { min-height: 160px; }
}
</style>
