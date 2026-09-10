<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import DOMPurify from 'dompurify'
import katex from 'katex'
import { marked } from 'marked'
import 'katex/dist/katex.min.css'

const draft = ref('')
const apiKey = ref('')
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
  const assistantIndex = messages.value.length
  messages.value.push({ role: 'assistant', content: '' })
  trace.value = []
  sending.value = true
  await scrollTranscript()

  try {
    const response = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: prompt, apiKey: apiKey.value.trim() || null })
    })
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      throw new Error(payload.error || '请求失败')
    }

    await consumeSse(response, (event, data) => {
      if (event === 'delta') {
        messages.value[assistantIndex].content += typeof data === 'string' ? data : ''
      } else if (event === 'tool_call') {
        trace.value.push({ type: 'tool', name: data.name, arguments: data.arguments, result: null })
      } else if (event === 'tool_result') {
        const pending = [...trace.value].reverse().find((item) => item.type === 'tool' && item.name === data.name && !item.result)
        if (pending) pending.result = data.result
        else trace.value.push(data)
      } else if (event === 'done') {
        messages.value[assistantIndex].content = data.answer || messages.value[assistantIndex].content
        trace.value = data.trace || trace.value
        history.value.unshift({
          prompt,
          answer: data.answer,
          turns: data.turns || 0,
          tools: (data.trace || []).filter((item) => item.type === 'tool').length,
          time: new Date()
        })
      } else if (event === 'error') {
        throw new Error(data.error || '运行失败')
      }
    })
  } catch (requestError) {
    error.value = requestError.message
    messages.value.splice(assistantIndex, 1)
    messages.value.push({ role: 'error', content: requestError.message })
  } finally {
    sending.value = false
    await scrollTranscript()
  }
}

async function consumeSse(response, onEvent) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    buffer = buffer.replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const parsed = parseSseBlock(block)
      if (parsed) onEvent(parsed.event, parsed.data)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }
}

function parseSseBlock(block) {
  let event = 'message'
  const data = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (data.length === 0) return null
  const raw = data.join('\n')
  try {
    return { event, data: JSON.parse(raw) }
  } catch {
    return { event, data: raw }
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

function renderMarkdown(content) {
  const formulas = []
  const source = (content || '').replace(
    /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|(?<!\$)\$([^\n$]+?)(?<!\$)\$/g,
    (match, blockDollar, blockBracket, inlineBracket, inlineDollar) => {
      const displayMode = blockDollar !== undefined || blockBracket !== undefined
      const expression = blockDollar ?? blockBracket ?? inlineBracket ?? inlineDollar
      const token = `<${displayMode ? 'div' : 'span'} data-dsh-math="${formulas.length}"></${displayMode ? 'div' : 'span'}>`
      formulas.push({ token, expression, displayMode })
      return displayMode ? `\n\n${token}\n\n` : token
    }
  )

  let html = marked.parse(source, { breaks: true, gfm: true })
  for (const formula of formulas) {
    let rendered
    try {
      rendered = katex.renderToString(formula.expression.trim(), {
        displayMode: formula.displayMode,
        throwOnError: false,
        output: 'htmlAndMathml'
      })
    } catch {
      rendered = `<code>${formula.expression}</code>`
    }
    html = html.replaceAll(formula.token, rendered)
  }
  return DOMPurify.sanitize(html)
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
          <label class="api-key-control">
            <span>DEBUG API KEY</span>
            <input
              v-model="apiKey"
              type="password"
              autocomplete="off"
              spellcheck="false"
              placeholder="Optional"
              :disabled="sending"
            />
          </label>
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
            <div v-if="item.role === 'assistant'" class="message-content markdown-content" v-html="renderMarkdown(item.content)"></div>
            <div v-else class="message-content">{{ item.content }}</div>
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
