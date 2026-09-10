<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'

const draft = ref('')
const messages = ref([
  {
    role: 'assistant',
    content: '准备好了。输入一个问题，我会把模型回答和工具调用过程拆开显示。'
  }
])
const trace = ref([])
const history = ref([])
const runtime = ref({ runtimeStarted: false, pluginCount: 0 })
const sending = ref(false)
const error = ref('')
const transcript = ref(null)
const apiPort = (() => {
  const target = import.meta.env.VITE_API_TARGET || 'http://localhost:8080'
  try {
    return new URL(target).port || '80'
  } catch {
    return '8080'
  }
})()

const toolCount = computed(() => trace.value.filter((item) => item.type === 'tool').length)
const modelCount = computed(() => trace.value.filter((item) => item.type === 'model').length)
const canSend = computed(() => draft.value.trim().length > 0 && !sending.value)

async function refreshHealth() {
  try {
    const response = await fetch('/api/v1/health')
    if (!response.ok) throw new Error('runtime unavailable')
    runtime.value = await response.json()
  } catch {
    runtime.value = { runtimeStarted: false, pluginCount: 0 }
  }
}

async function sendMessage() {
  if (!canSend.value) return
  const prompt = draft.value.trim()
  draft.value = ''
  error.value = ''
  messages.value.push({ role: 'user', content: prompt })
  sending.value = true
  await scrollTranscript()

  try {
    const response = await fetch('/api/v1/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: prompt })
    })
    const payload = await response.json()
    if (!response.ok) throw new Error(payload.error || '请求失败')
    messages.value.push({ role: 'assistant', content: payload.message })
    trace.value = payload.trace || []
    history.value.unshift({
      prompt,
      answer: payload.message,
      turns: payload.turns || 0,
      tools: (payload.trace || []).filter((item) => item.type === 'tool').length,
      time: new Date()
    })
  } catch (requestError) {
    error.value = requestError.message
    messages.value.push({ role: 'error', content: requestError.message })
  } finally {
    sending.value = false
    await scrollTranscript()
  }
}

function handleComposerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

function clearConversation() {
  messages.value = []
  trace.value = []
  error.value = ''
}

function formatArguments(argumentsNode) {
  if (!argumentsNode) return '{}'
  return JSON.stringify(argumentsNode, null, 2)
}

function formatTime(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit'
  }).format(value)
}

async function scrollTranscript() {
  await nextTick()
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight
}

onMounted(refreshHealth)
</script>

<template>
  <main class="app-shell">
    <aside class="sidebar">
      <div class="brand-lockup">
        <div class="brand-mark">D</div>
        <div>
          <div class="brand-name">DSH</div>
          <div class="brand-subtitle">AGENT LAB</div>
        </div>
      </div>

      <div class="runtime-card">
        <div class="eyebrow">RUNTIME</div>
        <div class="runtime-row">
          <span class="status-dot" :class="{ online: runtime.runtimeStarted }"></span>
          <strong>{{ runtime.runtimeStarted ? 'Connected' : 'Offline' }}</strong>
          <span class="runtime-port">:{{ apiPort }}</span>
        </div>
        <div class="runtime-meta">{{ runtime.pluginCount }} plugins registered</div>
      </div>

      <button class="new-run-button" type="button" @click="clearConversation">
        <span class="button-icon">+</span>
        New run
      </button>

      <div class="sidebar-section">
        <div class="section-label">RECENT RUNS</div>
        <div v-if="history.length === 0" class="empty-history">No runs yet</div>
        <button v-for="item in history" :key="item.time.getTime()" class="history-item" type="button">
          <span class="history-title">{{ item.prompt }}</span>
          <span class="history-meta">{{ item.tools }} tools · {{ formatTime(item.time) }}</span>
        </button>
      </div>

      <div class="sidebar-footer">
        <span class="footer-label">MODEL</span>
        <span class="model-name">deepseek-v4-flash</span>
        <span class="version">v0.1.0</span>
      </div>
    </aside>

    <section class="workspace">
      <header class="workspace-header">
        <div>
          <div class="eyebrow">CONVERSATION DEBUGGER</div>
          <h1>Agent Playground</h1>
        </div>
        <div class="header-actions">
          <span class="trace-summary">{{ modelCount }} model · {{ toolCount }} tools</span>
          <button class="icon-button" type="button" title="Clear current run" aria-label="Clear current run" @click="clearConversation">⌫</button>
        </div>
      </header>

      <div ref="transcript" class="transcript">
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-glyph">↗</div>
          <h2>Start a tool-aware run</h2>
          <p>Ask a question and inspect every model turn on the right.</p>
        </div>

        <article v-for="(item, index) in messages" :key="`${item.role}-${index}`" class="message-row" :class="item.role">
          <div class="message-avatar">{{ item.role === 'user' ? 'S' : item.role === 'error' ? '!' : 'D' }}</div>
          <div class="message-body">
            <div class="message-meta">
              <strong>{{ item.role === 'user' ? 'You' : item.role === 'error' ? 'Runtime' : 'DSH Agent' }}</strong>
              <span>{{ item.role === 'user' ? 'prompt' : item.role === 'error' ? 'error' : 'answer' }}</span>
            </div>
            <div class="message-content">{{ item.content }}</div>
          </div>
        </article>

        <div v-if="sending" class="thinking-row">
          <span class="thinking-dots"><i></i><i></i><i></i></span>
          Agent is working through the run…
        </div>
      </div>

      <form class="composer" @submit.prevent="sendMessage">
        <textarea
          v-model="draft"
          rows="3"
          placeholder="Ask the agent something…"
          :disabled="sending"
          @keydown="handleComposerKeydown"
        ></textarea>
        <div class="composer-footer">
          <span class="composer-hint">Enter to send · Shift + Enter for a new line</span>
          <button class="send-button" type="submit" :disabled="!canSend">
            <span>{{ sending ? 'Running' : 'Run agent' }}</span>
            <span class="send-arrow">↗</span>
          </button>
        </div>
      </form>
    </section>

    <aside class="trace-panel">
      <header class="trace-header">
        <div>
          <div class="eyebrow">OBSERVABILITY</div>
          <h2>Run trace</h2>
        </div>
        <span class="trace-count">{{ trace.length }}</span>
      </header>

      <div v-if="trace.length === 0" class="trace-empty">
        <div class="trace-empty-line"></div>
        <p>Tool calls and model turns will appear here after a run.</p>
      </div>

      <div v-else class="trace-list">
        <article v-for="(event, index) in trace" :key="`${event.type}-${index}`" class="trace-event" :class="event.type">
          <div class="trace-marker">{{ event.type === 'tool' ? 'T' : 'M' }}</div>
          <div class="trace-event-body">
            <div class="trace-event-head">
              <strong>{{ event.type === 'tool' ? event.name : 'Model response' }}</strong>
              <span>#{{ index + 1 }}</span>
            </div>
            <p v-if="event.type === 'model'" class="trace-content">{{ event.content }}</p>
            <template v-else>
              <div class="trace-block-label">ARGUMENTS</div>
              <pre>{{ formatArguments(event.arguments) }}</pre>
              <div class="trace-block-label">RESULT</div>
              <pre class="result">{{ event.result }}</pre>
            </template>
          </div>
        </article>
      </div>
    </aside>
  </main>
</template>
