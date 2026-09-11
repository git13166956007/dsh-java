<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import DOMPurify from 'dompurify'
import katex from 'katex'
import { marked } from 'marked'
import 'katex/dist/katex.min.css'

const draft = ref('')
const apiKey = ref('')
const conversationId = ref(null)
const messages = ref([
  {
    role: 'assistant',
    content: '准备好了。输入一个问题，我会把模型回答和工具调用过程拆开显示。'
  }
])
const trace = ref([])
const history = ref([])
const runtime = ref({ runtimeStarted: false, persistenceEnabled: false, pluginCount: 0 })
const pluginLoading = ref(false)
const tools = ref([])
const toolManagerOpen = ref(false)
const capabilityTab = ref('tools')
const mcpServers = ref([])
const skills = ref([])
const skillForm = ref({ id: '', name: '', version: '0.1.0', description: '', content: '', enabled: true })
const skillEditingId = ref(null)
const skillFormError = ref('')
const skillSaving = ref(false)
const models = ref([])
const modelProviders = ref([])
const workspaces = ref([])
const workspaceForm = ref({
  id: null,
  name: '',
  directory: '.',
  enabled: true,
  active: false,
  writeEnabled: false,
  maxReadBytes: 1000000,
  maxWriteBytes: 1000000,
  maxProcessTimeoutSeconds: 120,
  maxProcessOutputBytes: 1000000,
  allowedCommands: ''
})
const workspaceFormError = ref('')
const workspaceSaving = ref(false)
const contextInfo = ref(null)
const contextProviders = ref([])
const contextLoading = ref(false)
const contextError = ref('')
const selectedModelId = ref(null)
const agents = ref([])
const selectedAgentId = ref(null)
const selectedMode = ref('chat')
const subAgents = ref([])
const subAgentForm = ref({
  id: null,
  name: '',
  mode: 'execution',
  modelId: '',
  systemPrompt: '',
  maxTurns: 8,
  maxToolCalls: 64,
  timeoutSeconds: 300,
  maxDepth: 4,
  priority: 50,
  costWeight: 1,
  maxConcurrentRuns: 4,
  capabilityTags: '',
  allowedToolNames: '',
  skillIds: '',
  enabled: true
})
const subAgentFormError = ref('')
const subAgentSaving = ref(false)
const subAgentCandidateTask = ref('')
const subAgentCandidates = ref([])
const subAgentCandidateError = ref('')
const subAgentRunPrompt = ref('')
const subAgentRunProfileId = ref('')
const subAgentRunError = ref('')
const subAgentRunStarting = ref(false)
const subAgentRun = ref(null)
const subAgentRunEvents = ref([])
const subAgentSessions = ref([])
const subAgentSessionId = ref('')
const subAgentSessionPrompt = ref('')
const subAgentSessionError = ref('')
const subAgentSessionSaving = ref(false)
let subAgentRunPollTimer = null
let subAgentEventSource = null
let subAgentEventRunId = null
const memories = ref([])
const memoryForm = ref({ memoryType: 'fact', content: '', importance: 0.5 })
const memoryEditingId = ref(null)
const memoryFormError = ref('')
const memorySaving = ref(false)
const agentForm = ref({
  id: null,
  name: '',
  mode: 'chat',
  modelId: '',
  systemPrompt: '',
  maxTurns: 8,
  maxToolCalls: 64,
  timeoutSeconds: 300,
  maxDepth: 4,
  enabled: true,
  active: false
})
const agentFormError = ref('')
const agentSaving = ref(false)
const plans = ref([])
const selectedPlanId = ref(null)
const planDetail = ref(null)
const planApprovalRun = ref(null)
const planEvents = ref([])
const planForm = ref({
  title: '',
  goal: '',
  agentId: '',
  modelId: '',
  approvalRequired: true,
  maxConcurrency: 1,
  steps: [{ title: '', instruction: '', maxAttempts: 1, subAgentId: '', dependsOn: '' }]
})
const planFormError = ref('')
const planSaving = ref(false)
const adaptivePlanForm = ref({ prompt: '', maxSteps: 6, maxConcurrency: 1, approvalRequired: true, allowDynamicSubAgents: false })
const adaptivePlanOutput = ref('')
const adaptivePlanReasoning = ref('')
let planPollTimer = null
let planEventSource = null
let planEventRunId = null
const modelForm = ref({
  id: null,
  name: '',
  provider: 'deepseek',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-v4-flash',
  apiKey: '',
  clearApiKey: false,
  proxyHost: '',
  proxyPort: '',
  enabled: true,
  active: false,
  supportsTools: true,
  supportsStreaming: true,
  supportsVision: false,
  contextWindow: 0,
  temperature: '',
  topP: '',
  maxTokens: '',
  frequencyPenalty: '',
  presencePenalty: '',
  timeoutSeconds: 120,
  requestOptionsJson: '',
  fallbackModelId: '',
  failoverPolicy: 'any_failure',
  inputPricePerMillionTokens: '',
  outputPricePerMillionTokens: ''
})
const modelFormError = ref('')
const modelSaving = ref(false)
const modelTesting = ref(null)
const modelCatalog = ref([])
const modelCatalogLoading = ref(false)
const modelCatalogError = ref('')
const modelCatalogSelection = ref([])
const modelCatalogSaving = ref(false)
const mcpForm = ref({ name: '', transport: 'stdio', endpoint: '', command: '', arguments: '', credentialRef: '', headers: '{}', environment: '{}', approvalRequired: true })
const mcpFormError = ref('')
const mcpSaving = ref(false)
const toolForm = ref({
  name: '',
  description: '',
  parameters: '{\n  "type": "object",\n  "properties": {}\n}',
  result: '',
  approvalRequired: false
})
const toolFormError = ref('')
const toolSaving = ref(false)
const sending = ref(false)
const error = ref('')
const transcript = ref(null)
const conversationSearchQuery = ref('')
const conversationSearchResults = ref([])
const conversationActionError = ref('')
const conversationActionLoading = ref(false)
const conversations = ref([])
const conversationListQuery = ref('')
const conversationListResults = ref([])
const apiPort = (() => {
  const target = import.meta.env.VITE_API_TARGET || 'http://localhost:18080'
  try {
    return new URL(target).port || '80'
  } catch {
    return '18080'
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
    runtime.value = { runtimeStarted: false, persistenceEnabled: false, pluginCount: 0 }
  }
}

async function loadPlugins() {
  if (pluginLoading.value) return
  pluginLoading.value = true
  try {
    const response = await fetch('/api/v1/plugins/load', { method: 'POST' })
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || '插件加载失败')
    runtime.value = { ...runtime.value, pluginCount: payload.length, plugins: payload }
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    pluginLoading.value = false
  }
}

async function unloadPlugins() {
  if (pluginLoading.value) return
  pluginLoading.value = true
  try {
    const response = await fetch('/api/v1/plugins/unload', { method: 'POST' })
    if (!response.ok) throw new Error('Unable to unload plugins')
    await refreshRuntime()
    await refreshTools()
  } catch (requestError) {
    error.value = requestError.message
  } finally {
    pluginLoading.value = false
  }
}

async function refreshTools() {
  try {
    const response = await fetch('/api/v1/tools')
    if (!response.ok) throw new Error('工具列表不可用')
    tools.value = await response.json()
  } catch {
    tools.value = []
  }
}

async function refreshMcpServers() {
  try {
    const response = await fetch('/api/v1/mcp/servers')
    if (!response.ok) throw new Error('MCP 列表不可用')
    const servers = await response.json()
    mcpServers.value = await Promise.all(servers.map(async (server) => {
      try {
        const healthResponse = await fetch(`/api/v1/mcp/servers/${encodeURIComponent(server.id)}/health`)
        return { ...server, health: healthResponse.ok ? await healthResponse.json() : null }
      } catch {
        return { ...server, health: null }
      }
    }))
  } catch {
    mcpServers.value = []
  }
}

async function refreshSkills() {
  try {
    const response = await fetch('/api/v1/skills')
    if (!response.ok) throw new Error('Skills 列表不可用')
    skills.value = await response.json()
  } catch {
    skills.value = []
  }
}

async function refreshModels() {
  try {
    const [response, providersResponse] = await Promise.all([
      fetch('/api/v1/models'),
      fetch('/api/v1/models/providers')
    ])
    if (!response.ok) throw new Error('模型列表不可用')
    const profiles = await response.json()
    modelProviders.value = providersResponse.ok ? await providersResponse.json() : []
    models.value = await Promise.all(profiles.map(async (model) => {
      try {
        const [healthResponse, usageResponse] = await Promise.all([
          fetch(`/api/v1/models/${encodeURIComponent(model.id)}/health`),
          fetch(`/api/v1/models/${encodeURIComponent(model.id)}/usage`)
        ])
        model.health = healthResponse.ok ? await healthResponse.json() : null
        model.usage = usageResponse.ok ? await usageResponse.json() : null
      } catch {
        model.health = null
        model.usage = null
      }
      return model
    }))
    if (!selectedModelId.value || !models.value.some((model) => model.id === selectedModelId.value)) {
      selectedModelId.value = models.value.find((model) => model.active && model.enabled)?.id || models.value.find((model) => model.enabled)?.id || null
    }
  } catch {
    models.value = []
    modelProviders.value = []
    selectedModelId.value = null
  }
}

async function refreshAgents() {
  try {
    const response = await fetch('/api/v1/agents')
    if (!response.ok) throw new Error('Agent profiles unavailable')
    agents.value = await response.json()
    if (!selectedAgentId.value || !agents.value.some((agent) => agent.id === selectedAgentId.value)) {
      selectedAgentId.value = agents.value.find((agent) => agent.active && agent.enabled)?.id || agents.value.find((agent) => agent.enabled)?.id || null
    }
    const selected = agents.value.find((agent) => agent.id === selectedAgentId.value)
    if (selected) selectedMode.value = String(selected.mode || 'CHAT').toLowerCase()
  } catch {
    agents.value = []
    selectedAgentId.value = null
  }
}

async function refreshSubAgents() {
  try {
    const response = await fetch('/api/v1/sub-agents')
    if (!response.ok) throw new Error('Sub-agent profiles unavailable')
    subAgents.value = await response.json()
  } catch {
    subAgents.value = []
  }
}

async function refreshSubAgentSessions() {
  try {
    const response = await fetch('/api/v1/sub-agents/sessions')
    if (!response.ok) throw new Error('Sub-agent sessions unavailable')
    subAgentSessions.value = await response.json()
    if (!subAgentSessionId.value || !subAgentSessions.value.some((session) => session.id === subAgentSessionId.value)) {
      subAgentSessionId.value = subAgentSessions.value.find((session) => session.status === 'OPEN')?.id || ''
    }
  } catch {
    subAgentSessions.value = []
    subAgentSessionId.value = ''
  }
}

async function createSubAgentSession() {
  subAgentSessionError.value = ''
  if (!subAgentRunProfileId.value) {
    subAgentSessionError.value = '请选择一个启用的子智能体 Profile'
    return
  }
  subAgentSessionSaving.value = true
  try {
    const response = await fetch(`/api/v1/sub-agents/${encodeURIComponent(subAgentRunProfileId.value)}/sessions`, { method: 'POST' })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Session 创建失败')
    subAgentSessionId.value = payload.id
    await refreshSubAgentSessions()
  } catch (requestError) {
    subAgentSessionError.value = requestError.message
  } finally {
    subAgentSessionSaving.value = false
  }
}

async function sendSubAgentSessionMessage() {
  subAgentSessionError.value = ''
  if (!subAgentSessionId.value || !subAgentSessionPrompt.value.trim()) {
    subAgentSessionError.value = '请选择 Session 并填写消息'
    return
  }
  subAgentSessionSaving.value = true
  try {
    const response = await fetch(`/api/v1/sub-agents/sessions/${encodeURIComponent(subAgentSessionId.value)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: subAgentSessionPrompt.value.trim(), apiKey: apiKey.value.trim() || null })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || '消息发送失败')
    subAgentRun.value = payload
    subAgentSessionPrompt.value = ''
    subAgentRunEvents.value = []
    openSubAgentEventStream(payload.id)
    await refreshSubAgentRun()
  } catch (requestError) {
    subAgentSessionError.value = requestError.message
  } finally {
    subAgentSessionSaving.value = false
  }
}

async function closeSubAgentSession() {
  if (!subAgentSessionId.value) return
  const response = await fetch(`/api/v1/sub-agents/sessions/${encodeURIComponent(subAgentSessionId.value)}/close`, { method: 'POST' })
  if (response.ok) await refreshSubAgentSessions()
}

function memorySubjectKey() {
  return conversationId.value || 'global'
}

async function refreshMemories() {
  try {
    const params = new URLSearchParams({ namespace: 'conversation', subjectKey: memorySubjectKey(), limit: '50' })
    const response = await fetch(`/api/v1/memories?${params}`)
    if (!response.ok) throw new Error('Memories unavailable')
    memories.value = await response.json()
  } catch {
    memories.value = []
  }
}

async function refreshContext() {
  if (!conversationId.value) {
    contextInfo.value = null
    return
  }
  try {
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversationId.value)}/context`)
    if (!response.ok) throw new Error('Context unavailable')
    contextInfo.value = await response.json()
    contextError.value = ''
  } catch {
    contextInfo.value = null
  }
}

async function refreshContextProviders() {
  try {
    const response = await fetch('/api/v1/context/providers')
    if (!response.ok) throw new Error('Context providers unavailable')
    contextProviders.value = await response.json()
  } catch {
    contextProviders.value = []
  }
}

async function compactContext() {
  if (!conversationId.value || contextLoading.value) return
  contextLoading.value = true
  contextError.value = ''
  try {
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversationId.value)}/compact`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey: apiKey.value.trim() || null, modelId: selectedModelId.value })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Context compaction failed')
    contextInfo.value = payload
  } catch (requestError) {
    contextError.value = requestError.message
  } finally {
    contextLoading.value = false
  }
}

async function saveMemory() {
  memoryFormError.value = ''
  if (!memoryForm.value.content.trim()) {
    memoryFormError.value = 'Please provide memory content'
    return
  }
  memorySaving.value = true
  try {
    const editing = memoryEditingId.value !== null
    const response = await fetch(editing ? `/api/v1/memories/${encodeURIComponent(memoryEditingId.value)}` : '/api/v1/memories', {
      method: editing ? 'PATCH' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(editing
        ? { memoryType: memoryForm.value.memoryType, content: memoryForm.value.content.trim(), importance: Number(memoryForm.value.importance) || 0 }
        : { namespace: 'conversation', subjectKey: memorySubjectKey(), memoryType: memoryForm.value.memoryType, content: memoryForm.value.content.trim(), importance: Number(memoryForm.value.importance) || 0.5 })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Memory save failed')
    memoryForm.value = { memoryType: 'fact', content: '', importance: 0.5 }
    memoryEditingId.value = null
    await refreshMemories()
  } catch (requestError) {
    memoryFormError.value = requestError.message
  } finally {
    memorySaving.value = false
  }
}

function editMemory(memory) {
  memoryEditingId.value = memory.id
  memoryForm.value = {
    memoryType: memory.memoryType,
    content: memory.content,
    importance: memory.importance
  }
  memoryFormError.value = ''
}

function resetMemoryForm() {
  memoryEditingId.value = null
  memoryForm.value = { memoryType: 'fact', content: '', importance: 0.5 }
  memoryFormError.value = ''
}

async function deleteMemory(memory) {
  const response = await fetch(`/api/v1/memories/${memory.id}`, { method: 'DELETE' })
  if (response.ok) {
    if (memoryEditingId.value === memory.id) resetMemoryForm()
    await refreshMemories()
  }
}

function resetSubAgentForm() {
  subAgentForm.value = {
    id: null,
    name: '',
    mode: 'execution',
    modelId: '',
    systemPrompt: '',
    maxTurns: 8,
    maxToolCalls: 64,
    timeoutSeconds: 300,
    maxDepth: 4,
    priority: 50,
    costWeight: 1,
    maxConcurrentRuns: 4,
    capabilityTags: '',
    allowedToolNames: '',
    skillIds: '',
    enabled: true
  }
  subAgentFormError.value = ''
}

function editSubAgent(profile) {
  subAgentForm.value = {
    id: profile.id,
    name: profile.name,
    mode: String(profile.mode || 'EXECUTION').toLowerCase(),
    modelId: profile.modelId || '',
    systemPrompt: profile.systemPrompt || '',
    maxTurns: profile.maxTurns || 8,
    maxToolCalls: profile.maxToolCalls ?? 64,
    timeoutSeconds: profile.timeoutSeconds ?? 300,
    maxDepth: profile.maxDepth ?? 4,
    priority: profile.priority ?? 50,
    costWeight: profile.costWeight ?? 1,
    maxConcurrentRuns: profile.maxConcurrentRuns ?? 4,
    capabilityTags: (profile.capabilityTags || []).join(', '),
    allowedToolNames: (profile.allowedToolNames || []).join(', '),
    skillIds: (profile.skillIds || []).join(', '),
    enabled: profile.enabled
  }
  subAgentFormError.value = ''
}

async function saveSubAgent() {
  subAgentFormError.value = ''
  if (!subAgentForm.value.name.trim()) {
    subAgentFormError.value = 'Please provide a sub-agent name'
    return
  }
  subAgentSaving.value = true
  try {
    const editing = Boolean(subAgentForm.value.id)
    const response = await fetch(editing ? `/api/v1/sub-agents/${encodeURIComponent(subAgentForm.value.id)}` : '/api/v1/sub-agents', {
      method: editing ? 'PATCH' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: subAgentForm.value.name.trim(),
        mode: subAgentForm.value.mode,
        modelId: subAgentForm.value.modelId.trim() || null,
        systemPrompt: subAgentForm.value.systemPrompt,
        maxTurns: Number(subAgentForm.value.maxTurns) || 8,
        maxToolCalls: Number(subAgentForm.value.maxToolCalls) || 0,
        timeoutSeconds: Number(subAgentForm.value.timeoutSeconds) || 0,
        maxDepth: Number(subAgentForm.value.maxDepth) || 0,
        priority: Number(subAgentForm.value.priority) || 0,
        costWeight: Number(subAgentForm.value.costWeight),
        maxConcurrentRuns: Number(subAgentForm.value.maxConcurrentRuns) || 1,
        capabilityTags: subAgentForm.value.capabilityTags.split(',').map((value) => value.trim()).filter(Boolean),
        allowedToolNames: subAgentForm.value.allowedToolNames.split(',').map((value) => value.trim()).filter(Boolean),
        skillIds: subAgentForm.value.skillIds.split(',').map((value) => value.trim()).filter(Boolean),
        enabled: subAgentForm.value.enabled
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Sub-agent profile save failed')
    await refreshSubAgents()
    resetSubAgentForm()
  } catch (requestError) {
    subAgentFormError.value = requestError.message
  } finally {
    subAgentSaving.value = false
  }
}

async function inspectSubAgentCandidates() {
  subAgentCandidateError.value = ''
  subAgentCandidates.value = []
  if (!subAgentCandidateTask.value.trim()) {
    subAgentCandidateError.value = 'Please provide a task'
    return
  }
  try {
    const response = await fetch(`/api/v1/sub-agents/candidates?task=${encodeURIComponent(subAgentCandidateTask.value.trim())}`)
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || 'Candidate inspection failed')
    subAgentCandidates.value = payload
  } catch (requestError) {
    subAgentCandidateError.value = requestError.message
  }
}

async function toggleSubAgent(profile) {
  const response = await fetch(`/api/v1/sub-agents/${encodeURIComponent(profile.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !profile.enabled })
  })
  if (response.ok) await refreshSubAgents()
}

async function deleteSubAgent(profile) {
  const response = await fetch(`/api/v1/sub-agents/${encodeURIComponent(profile.id)}`, { method: 'DELETE' })
  if (response.ok) {
    await refreshSubAgents()
    resetSubAgentForm()
  }
}

function prepareSubAgentRun(profile) {
  subAgentRunProfileId.value = profile.id
  subAgentRunPrompt.value = ''
  subAgentRunError.value = ''
}

function closeSubAgentEventStream() {
  if (subAgentEventSource) subAgentEventSource.close()
  subAgentEventSource = null
  subAgentEventRunId = null
}

function openSubAgentEventStream(runId) {
  if (!runId || subAgentEventRunId === runId) return
  closeSubAgentEventStream()
  subAgentRunEvents.value = []
  subAgentEventRunId = runId
  subAgentEventSource = new EventSource(`/api/v1/runs/${encodeURIComponent(runId)}/events/stream`)
  subAgentEventSource.addEventListener('run_event', (event) => {
    try {
      const value = JSON.parse(event.data)
      if (!subAgentRunEvents.value.some((item) => item.id === value.id && item.type === value.type)) {
        subAgentRunEvents.value.push(value)
      }
      if (['run_completed', 'run_failed', 'run_cancelled'].includes(value.type)) refreshSubAgentRun()
    } catch {
      // Polling remains the source of truth when a diagnostic event is malformed.
    }
  })
  subAgentEventSource.onerror = () => closeSubAgentEventStream()
}

async function refreshSubAgentRun() {
  if (!subAgentRun.value?.id) return
  try {
    const response = await fetch(`/api/v1/runs/${encodeURIComponent(subAgentRun.value.id)}`)
    if (!response.ok) return
    subAgentRun.value = await response.json()
    const status = String(subAgentRun.value.status || '').toUpperCase()
    if (['RUNNING', 'WAITING_APPROVAL'].includes(status)) {
      clearTimeout(subAgentRunPollTimer)
      subAgentRunPollTimer = setTimeout(refreshSubAgentRun, 1000)
    } else {
      closeSubAgentEventStream()
    }
  } catch {
    // Keep the last visible run state during a transient API failure.
  }
}

async function startSubAgentRun() {
  subAgentRunError.value = ''
  if (!subAgentRunProfileId.value || !subAgentRunPrompt.value.trim()) {
    subAgentRunError.value = '请选择子智能体并填写任务'
    return
  }
  subAgentRunStarting.value = true
  try {
    const response = await fetch(`/api/v1/sub-agents/${encodeURIComponent(subAgentRunProfileId.value)}/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: subAgentRunPrompt.value.trim(), apiKey: apiKey.value.trim() || null })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || '子智能体启动失败')
    subAgentRun.value = payload
    subAgentRunPrompt.value = ''
    subAgentRunEvents.value = []
    openSubAgentEventStream(payload.id)
    await refreshSubAgentRun()
  } catch (requestError) {
    subAgentRunError.value = requestError.message
  } finally {
    subAgentRunStarting.value = false
  }
}

async function cancelSubAgentRun() {
  if (!subAgentRun.value?.id) return
  const response = await fetch(`/api/v1/runs/${encodeURIComponent(subAgentRun.value.id)}/cancel`, { method: 'POST' })
  if (response.ok) await refreshSubAgentRun()
}

async function approveSubAgentRun(approved) {
  if (!subAgentRun.value?.id) return
  const response = await fetch(`/api/v1/runs/${encodeURIComponent(subAgentRun.value.id)}/approval`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approved, apiKey: apiKey.value.trim() || null })
  })
  if (response.ok) await refreshSubAgentRun()
}

async function refreshPlans() {
  try {
    const response = await fetch('/api/v1/plans')
    if (!response.ok) throw new Error('Plans unavailable')
    plans.value = await response.json()
    if (selectedPlanId.value && plans.value.some((plan) => plan.id === selectedPlanId.value)) {
      await refreshPlanDetail(selectedPlanId.value)
    }
  } catch {
    plans.value = []
    planDetail.value = null
  }
}

async function refreshPlanDetail(id) {
  const response = await fetch(`/api/v1/plans/${encodeURIComponent(id)}`)
  if (!response.ok) return
  planDetail.value = await response.json()
  const index = plans.value.findIndex((plan) => plan.id === id)
  if (index >= 0) plans.value[index] = planDetail.value
  try {
    const runsResponse = await fetch(`/api/v1/runs?planId=${encodeURIComponent(id)}`)
    const planRuns = runsResponse.ok ? await runsResponse.json() : []
    planApprovalRun.value = planRuns.find((run) => run.status === 'WAITING_APPROVAL') || null
    const planRun = planRuns.find((run) => String(run.kind).toUpperCase() === 'PLAN')
    if (planRun && ['RUNNING', 'WAITING_APPROVAL'].includes(String(planDetail.value.status).toUpperCase())) {
      openPlanEventStream(planRun.id)
    } else {
      closePlanEventStream()
    }
  } catch {
    planApprovalRun.value = null
    closePlanEventStream()
  }
  if (planDetail.value.status === 'RUNNING') {
    clearTimeout(planPollTimer)
    planPollTimer = setTimeout(() => refreshPlanDetail(id), 1200)
  }
}

function openPlanEventStream(runId) {
  if (!runId || planEventRunId === runId) return
  closePlanEventStream()
  planEvents.value = []
  planEventRunId = runId
  planEventSource = new EventSource(`/api/v1/runs/${encodeURIComponent(runId)}/events/stream`)
  planEventSource.addEventListener('run_event', (event) => {
    try {
      const value = JSON.parse(event.data)
      if (!planEvents.value.some((item) => item.id === value.id && item.type === value.type)) {
        planEvents.value.push(value)
      }
      if (['run_completed', 'run_failed', 'run_cancelled', 'tool_approval_required'].includes(value.type)) {
        refreshPlanDetail(selectedPlanId.value)
      }
    } catch {
      // Keep the polling fallback when a diagnostic event is malformed.
    }
  })
  planEventSource.onerror = () => {
    closePlanEventStream()
  }
}

function closePlanEventStream() {
  if (planEventSource) planEventSource.close()
  planEventSource = null
  planEventRunId = null
}

async function approvePlanRun(approved) {
  if (!planApprovalRun.value) return
  const runId = planApprovalRun.value.id
  const response = await fetch(`/api/v1/runs/${encodeURIComponent(runId)}/approval`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approved, apiKey: apiKey.value.trim() || null })
  })
  if (!response.ok) return
  planApprovalRun.value = null
  if (selectedPlanId.value) {
    await refreshPlanDetail(selectedPlanId.value)
    clearTimeout(planPollTimer)
    planPollTimer = setTimeout(() => refreshPlanDetail(selectedPlanId.value), 500)
  }
  await refreshPlans()
}

function resetPlanForm() {
  planForm.value = {
    title: '',
    goal: '',
    agentId: selectedAgentId.value || '',
    modelId: selectedModelId.value || '',
    approvalRequired: true,
    maxConcurrency: 1,
    steps: [{ title: '', instruction: '', maxAttempts: 1, subAgentId: '', dependsOn: '' }]
  }
  planFormError.value = ''
}

function addPlanStep() {
  planForm.value.steps.push({ title: '', instruction: '', maxAttempts: 1, subAgentId: '', dependsOn: '' })
}

function removePlanStep(index) {
  if (planForm.value.steps.length > 1) planForm.value.steps.splice(index, 1)
}

async function createPlan() {
  planFormError.value = ''
  if (!planForm.value.title.trim() || !planForm.value.goal.trim()
      || planForm.value.steps.some((step) => !step.title.trim() || !step.instruction.trim())) {
    planFormError.value = 'Please provide a title, goal, and instructions for every step'
    return
  }
  planSaving.value = true
  try {
    const response = await fetch('/api/v1/plans', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: planForm.value.title.trim(),
        goal: planForm.value.goal.trim(),
        agentId: planForm.value.agentId.trim() || null,
        modelId: planForm.value.modelId.trim() || null,
        approvalRequired: planForm.value.approvalRequired,
        maxConcurrency: Number(planForm.value.maxConcurrency) || 1,
        steps: planForm.value.steps.map((step) => ({
          title: step.title.trim(),
          instruction: step.instruction.trim(),
          maxAttempts: Number(step.maxAttempts) || 1,
          subAgentId: step.subAgentId.trim() || null,
          dependsOn: step.dependsOn.split(',').map((value) => Number(value.trim())).filter((value) => Number.isInteger(value) && value > 0)
        }))
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Plan creation failed')
    selectedPlanId.value = payload.id
    planDetail.value = payload
    await refreshPlans()
    resetPlanForm()
  } catch (requestError) {
    planFormError.value = requestError.message
  } finally {
    planSaving.value = false
  }
}

async function createAdaptivePlan() {
  planFormError.value = ''
  if (!adaptivePlanForm.value.prompt.trim()) {
    planFormError.value = 'Please describe the task for the planner'
    return
  }
  planSaving.value = true
  adaptivePlanOutput.value = ''
  adaptivePlanReasoning.value = ''
  try {
    const response = await fetch('/api/v1/plans/adaptive/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: adaptivePlanForm.value.prompt.trim(),
        apiKey: apiKey.value.trim() || null,
        agentId: selectedAgentId.value,
        modelId: selectedModelId.value,
        approvalRequired: adaptivePlanForm.value.approvalRequired,
        maxSteps: Number(adaptivePlanForm.value.maxSteps) || 6,
        maxConcurrency: Number(adaptivePlanForm.value.maxConcurrency) || 1,
        allowDynamicSubAgents: adaptivePlanForm.value.allowDynamicSubAgents
      })
    })
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      throw new Error(payload.message || payload.error || 'Adaptive plan creation failed')
    }
    let streamError = null
    await consumeSse(response, (event, data) => {
      if (event === 'planning_delta') adaptivePlanOutput.value += typeof data === 'string' ? data : ''
      else if (event === 'planning_reasoning_delta') adaptivePlanReasoning.value += typeof data === 'string' ? data : ''
      else if (event === 'plan_created' || event === 'done') {
        selectedPlanId.value = data.id
        planDetail.value = data
      } else if (event === 'error') {
        streamError = data?.error || data?.message || String(data)
      }
    })
    if (streamError) throw new Error(streamError)
    await refreshPlans()
    adaptivePlanForm.value.prompt = ''
  } catch (requestError) {
    planFormError.value = requestError.message
  } finally {
    planSaving.value = false
  }
}

async function planAction(plan, action) {
  const body = action === 'execute' ? JSON.stringify({ apiKey: apiKey.value.trim() || null }) : undefined
  const response = await fetch(`/api/v1/plans/${encodeURIComponent(plan.id)}/${action}`, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    planFormError.value = payload.message || payload.error || `Plan ${action} failed`
    return
  }
  selectedPlanId.value = plan.id
  planDetail.value = payload
  await refreshPlans()
}

function applyAgentSelection() {
  const selected = agents.value.find((agent) => agent.id === selectedAgentId.value)
  if (selected) selectedMode.value = String(selected.mode || 'CHAT').toLowerCase()
}

function openToolManager() {
  openCapabilities('tools')
}

function openCapabilities(tab) {
  toolFormError.value = ''
  mcpFormError.value = ''
  modelFormError.value = ''
  agentFormError.value = ''
  capabilityTab.value = tab
  toolManagerOpen.value = true
  refreshTools()
  refreshMcpServers()
  refreshSkills()
  refreshModels()
  refreshContextProviders()
  refreshAgents()
  refreshSubAgents()
  refreshSubAgentSessions()
  refreshMemories()
  refreshPlans()
}

function resetAgentForm() {
  agentForm.value = {
    id: null,
    name: '',
    mode: 'chat',
    modelId: '',
    systemPrompt: '',
    maxTurns: 8,
    maxToolCalls: 64,
    timeoutSeconds: 300,
    maxDepth: 4,
    enabled: true,
    active: false
  }
  agentFormError.value = ''
}

function editAgent(agent) {
  agentForm.value = {
    id: agent.id,
    name: agent.name,
    mode: String(agent.mode || 'CHAT').toLowerCase(),
    modelId: agent.modelId || '',
    systemPrompt: agent.systemPrompt || '',
    maxTurns: agent.maxTurns || 8,
    maxToolCalls: agent.maxToolCalls ?? 64,
    timeoutSeconds: agent.timeoutSeconds ?? 300,
    maxDepth: agent.maxDepth ?? 4,
    enabled: agent.enabled,
    active: agent.active
  }
  agentFormError.value = ''
}

async function saveAgent() {
  agentFormError.value = ''
  if (!agentForm.value.name.trim()) {
    agentFormError.value = 'Please provide a profile name'
    return
  }
  agentSaving.value = true
  try {
    const editing = Boolean(agentForm.value.id)
    const response = await fetch(editing ? `/api/v1/agents/${encodeURIComponent(agentForm.value.id)}` : '/api/v1/agents', {
      method: editing ? 'PATCH' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: agentForm.value.name.trim(),
        mode: agentForm.value.mode,
        modelId: agentForm.value.modelId.trim() || null,
        systemPrompt: agentForm.value.systemPrompt,
        maxTurns: Number(agentForm.value.maxTurns) || 8,
        maxToolCalls: Number(agentForm.value.maxToolCalls) || 0,
        timeoutSeconds: Number(agentForm.value.timeoutSeconds) || 0,
        maxDepth: Number(agentForm.value.maxDepth) || 0,
        enabled: agentForm.value.enabled,
        active: agentForm.value.active
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Agent profile save failed')
    await refreshAgents()
    selectedAgentId.value = payload.id
    applyAgentSelection()
    resetAgentForm()
  } catch (requestError) {
    agentFormError.value = requestError.message
  } finally {
    agentSaving.value = false
  }
}

async function activateAgent(agent) {
  const response = await fetch(`/api/v1/agents/${encodeURIComponent(agent.id)}/activate`, { method: 'POST' })
  if (!response.ok) return
  selectedAgentId.value = agent.id
  await refreshAgents()
}

async function toggleAgent(agent) {
  const response = await fetch(`/api/v1/agents/${encodeURIComponent(agent.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !agent.enabled })
  })
  if (!response.ok) return
  await refreshAgents()
}

async function deleteAgent(agent) {
  const response = await fetch(`/api/v1/agents/${encodeURIComponent(agent.id)}`, { method: 'DELETE' })
  if (!response.ok) return
  if (selectedAgentId.value === agent.id) selectedAgentId.value = null
  await refreshAgents()
  resetAgentForm()
}

function resetToolForm() {
  toolForm.value = {
    name: '',
    description: '',
    parameters: '{\n  "type": "object",\n  "properties": {}\n}',
    result: '',
    approvalRequired: false
  }
  toolFormError.value = ''
}

async function createTool() {
  toolFormError.value = ''
  let parameters
  try {
    parameters = JSON.parse(toolForm.value.parameters)
  } catch {
    toolFormError.value = 'Parameters 必须是有效的 JSON'
    return
  }
  if (!toolForm.value.name.trim() || !toolForm.value.description.trim() || !toolForm.value.result.trim()) {
    toolFormError.value = '请填写名称、描述和返回值'
    return
  }
  toolSaving.value = true
  try {
    const response = await fetch('/api/v1/tools', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: toolForm.value.name.trim(),
        description: toolForm.value.description.trim(),
        parameters,
        result: toolForm.value.result,
        approvalRequired: toolForm.value.approvalRequired
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || '工具创建失败')
    tools.value.push(payload)
    resetToolForm()
  } catch (requestError) {
    toolFormError.value = requestError.message
  } finally {
    toolSaving.value = false
  }
}

async function toggleTool(tool) {
  const response = await fetch(`/api/v1/tools/${encodeURIComponent(tool.name)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !tool.enabled })
  })
  if (!response.ok) return
  const updated = await response.json()
  Object.assign(tool, updated)
}

async function toggleToolApproval(tool) {
  const response = await fetch(`/api/v1/tools/${encodeURIComponent(tool.name)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approvalRequired: !tool.approvalRequired })
  })
  if (!response.ok) return
  Object.assign(tool, await response.json())
}

async function deleteTool(tool) {
  if (!tool.removable) return
  const response = await fetch(`/api/v1/tools/${encodeURIComponent(tool.name)}`, { method: 'DELETE' })
  if (response.ok) tools.value = tools.value.filter((item) => item.name !== tool.name)
}

function resetMcpForm() {
  mcpForm.value = { name: '', transport: 'stdio', endpoint: '', command: '', arguments: '', credentialRef: '', headers: '{}', environment: '{}', approvalRequired: true }
  mcpFormError.value = ''
}

function parseMcpMap(value, label) {
  if (!value.trim()) return {}
  const parsed = JSON.parse(value)
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error(`${label} must be a JSON object`)
  return Object.fromEntries(Object.entries(parsed).map(([key, item]) => [key, String(item)]))
}

async function createMcpServer() {
  mcpFormError.value = ''
  if (!mcpForm.value.name.trim()) {
    mcpFormError.value = '请填写 Server 名称'
    return
  }
  if (mcpForm.value.transport === 'stdio' && !mcpForm.value.command.trim()) {
    mcpFormError.value = 'stdio 需要填写启动命令'
    return
  }
  if (mcpForm.value.transport !== 'stdio' && !mcpForm.value.endpoint.trim()) {
    mcpFormError.value = 'HTTP transport 需要填写服务地址'
    return
  }
  mcpSaving.value = true
  try {
    const headers = parseMcpMap(mcpForm.value.headers, 'Headers')
    const environment = parseMcpMap(mcpForm.value.environment, 'Environment')
    const response = await fetch('/api/v1/mcp/servers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: mcpForm.value.name.trim(),
        transport: mcpForm.value.transport,
        endpoint: mcpForm.value.endpoint.trim() || null,
        command: mcpForm.value.command.trim() || null,
        arguments: mcpForm.value.arguments.split(/\s+/).filter(Boolean),
        credentialRef: mcpForm.value.credentialRef.trim() || null,
        headers,
        environment,
        approvalRequired: mcpForm.value.approvalRequired
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'MCP Server 创建失败')
    mcpServers.value.push(payload)
    resetMcpForm()
  } catch (requestError) {
    mcpFormError.value = requestError.message
  } finally {
    mcpSaving.value = false
  }
}

async function mcpAction(server, action) {
  const response = await fetch(`/api/v1/mcp/servers/${server.id}/${action}`, { method: 'POST' })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    mcpFormError.value = payload.message || payload.error || `MCP ${action} 失败`
    return
  }
  Object.assign(server, payload)
  await refreshMcpHealth(server)
  await refreshTools()
}

async function toggleMcpApproval(server) {
  const response = await fetch(`/api/v1/mcp/servers/${encodeURIComponent(server.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ approvalRequired: !server.approvalRequired })
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    mcpFormError.value = payload.message || payload.error || 'MCP approval setting failed'
    return
  }
  Object.assign(server, payload)
  await refreshTools()
}

async function refreshMcpHealth(server) {
  try {
    const response = await fetch(`/api/v1/mcp/servers/${encodeURIComponent(server.id)}/health`)
    server.health = response.ok ? await response.json() : null
  } catch {
    server.health = null
  }
}

async function inspectMcp(server, kind) {
  mcpFormError.value = ''
  try {
    const response = await fetch(`/api/v1/mcp/servers/${encodeURIComponent(server.id)}/${kind}`)
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || `MCP ${kind} 查询失败`)
    server[kind] = payload
  } catch (requestError) {
    mcpFormError.value = requestError.message
  }
}

async function readMcpResource(server, resource) {
  mcpFormError.value = ''
  try {
    const response = await fetch(`/api/v1/mcp/servers/${encodeURIComponent(server.id)}/resources/read?uri=${encodeURIComponent(resource.uri)}`)
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || 'MCP resource read failed')
    resource.content = payload
  } catch (requestError) {
    mcpFormError.value = requestError.message
  }
}

async function getMcpPrompt(server, prompt) {
  mcpFormError.value = ''
  try {
    const response = await fetch(`/api/v1/mcp/servers/${encodeURIComponent(server.id)}/prompts/${encodeURIComponent(prompt.name)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({})
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'MCP prompt load failed')
    prompt.result = payload
  } catch (requestError) {
    mcpFormError.value = requestError.message
  }
}

async function deleteMcpServer(server) {
  const response = await fetch(`/api/v1/mcp/servers/${server.id}`, { method: 'DELETE' })
  if (response.ok) {
    mcpServers.value = mcpServers.value.filter((item) => item.id !== server.id)
    await refreshTools()
  }
}

async function toggleSkill(skill) {
  const response = await fetch(`/api/v1/skills/${encodeURIComponent(skill.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !skill.enabled })
  })
  if (!response.ok) return
  Object.assign(skill, await response.json())
}

function resetSkillForm() {
  skillForm.value = { id: '', name: '', version: '0.1.0', description: '', content: '', enabled: true }
  skillEditingId.value = null
  skillFormError.value = ''
}

function editSkill(skill) {
  skillForm.value = {
    id: skill.id,
    name: skill.name || '',
    version: skill.version || '0.1.0',
    description: skill.description || '',
    content: skill.content || '',
    enabled: skill.enabled !== false
  }
  skillEditingId.value = skill.id
  skillFormError.value = ''
}

async function saveSkill() {
  skillFormError.value = ''
  if (!skillForm.value.id.trim() || !skillForm.value.content.trim()) {
    skillFormError.value = '请填写 Skill ID 和正文'
    return
  }
  skillSaving.value = true
  try {
    const response = await fetch('/api/v1/skills', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: skillForm.value.id.trim(),
        name: skillForm.value.name.trim() || null,
        version: skillForm.value.version.trim() || null,
        description: skillForm.value.description.trim() || null,
        content: skillForm.value.content,
        enabled: skillForm.value.enabled
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Skill 保存失败')
    const index = skills.value.findIndex((skill) => skill.id === payload.id)
    if (index >= 0) skills.value[index] = payload
    else skills.value.push(payload)
    resetSkillForm()
  } catch (requestError) {
    skillFormError.value = requestError.message
  } finally {
    skillSaving.value = false
  }
}

async function deleteSkill(skill) {
  const response = await fetch(`/api/v1/skills/${encodeURIComponent(skill.id)}`, { method: 'DELETE' })
  if (!response.ok) return
  skills.value = skills.value.filter((item) => item.id !== skill.id)
  if (skillEditingId.value === skill.id) resetSkillForm()
}

function resetModelForm() {
  modelForm.value = {
    id: null,
    name: '',
    provider: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-v4-flash',
    apiKey: '',
    clearApiKey: false,
    proxyHost: '',
    proxyPort: '',
    enabled: true,
    active: false,
    supportsTools: true,
    supportsStreaming: true,
    supportsVision: false,
    contextWindow: 0,
    temperature: '',
    topP: '',
    maxTokens: '',
    frequencyPenalty: '',
    presencePenalty: '',
    timeoutSeconds: 120,
    requestOptionsJson: '',
    fallbackModelId: '',
    failoverPolicy: 'any_failure',
    inputPricePerMillionTokens: '',
    outputPricePerMillionTokens: ''
  }
  modelFormError.value = ''
  modelCatalog.value = []
  modelCatalogError.value = ''
  modelCatalogSelection.value = []
}

function resetWorkspaceForm() {
  workspaceForm.value = {
    id: null,
    name: '',
    directory: '.',
    enabled: true,
    active: false,
    writeEnabled: false,
    maxReadBytes: 1000000,
    maxWriteBytes: 1000000,
    maxProcessTimeoutSeconds: 120,
    maxProcessOutputBytes: 1000000,
    allowedCommands: ''
  }
  workspaceFormError.value = ''
}

function editWorkspace(workspace) {
  workspaceForm.value = {
    id: workspace.id,
    name: workspace.name,
    directory: workspace.directory,
    enabled: workspace.enabled,
    active: workspace.active,
    writeEnabled: workspace.writeEnabled,
    maxReadBytes: workspace.maxReadBytes,
    maxWriteBytes: workspace.maxWriteBytes,
    maxProcessTimeoutSeconds: workspace.maxProcessTimeoutSeconds,
    maxProcessOutputBytes: workspace.maxProcessOutputBytes,
    allowedCommands: (workspace.allowedCommands || []).join(', ')
  }
  workspaceFormError.value = ''
}

async function refreshWorkspaces() {
  const response = await fetch('/api/v1/workspaces')
  const payload = await response.json().catch(() => [])
  if (!response.ok) throw new Error(payload.message || payload.error || 'Workspace 加载失败')
  workspaces.value = payload
}

async function saveWorkspace() {
  workspaceFormError.value = ''
  if (!workspaceForm.value.name.trim() || !workspaceForm.value.directory.trim()) {
    workspaceFormError.value = '请填写名称和目录'
    return
  }
  workspaceSaving.value = true
  try {
    const editing = Boolean(workspaceForm.value.id)
    const response = await fetch(editing ? `/api/v1/workspaces/${encodeURIComponent(workspaceForm.value.id)}` : '/api/v1/workspaces', {
      method: editing ? 'PATCH' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: workspaceForm.value.name.trim(),
        directory: workspaceForm.value.directory.trim(),
        enabled: workspaceForm.value.enabled,
        active: workspaceForm.value.active,
        writeEnabled: workspaceForm.value.writeEnabled,
        maxReadBytes: Number(workspaceForm.value.maxReadBytes),
        maxWriteBytes: Number(workspaceForm.value.maxWriteBytes),
        maxProcessTimeoutSeconds: Number(workspaceForm.value.maxProcessTimeoutSeconds),
        maxProcessOutputBytes: Number(workspaceForm.value.maxProcessOutputBytes),
        allowedCommands: workspaceForm.value.allowedCommands.split(',').map((item) => item.trim()).filter(Boolean)
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Workspace 保存失败')
    const index = workspaces.value.findIndex((item) => item.id === payload.id)
    if (index >= 0) workspaces.value[index] = payload
    else workspaces.value.push(payload)
    resetWorkspaceForm()
  } catch (requestError) {
    workspaceFormError.value = requestError.message
  } finally {
    workspaceSaving.value = false
  }
}

async function activateWorkspace(workspace) {
  const response = await fetch(`/api/v1/workspaces/${encodeURIComponent(workspace.id)}/activate`, { method: 'POST' })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) return
  await refreshWorkspaces()
  workspaceForm.value.active = payload.active
}

async function toggleWorkspace(workspace) {
  const response = await fetch(`/api/v1/workspaces/${encodeURIComponent(workspace.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !workspace.enabled })
  })
  if (response.ok) await refreshWorkspaces()
}

async function deleteWorkspace(workspace) {
  const response = await fetch(`/api/v1/workspaces/${encodeURIComponent(workspace.id)}`, { method: 'DELETE' })
  if (!response.ok) return
  workspaces.value = workspaces.value.filter((item) => item.id !== workspace.id)
  if (workspaceForm.value.id === workspace.id) resetWorkspaceForm()
  await refreshWorkspaces()
}

function editModel(model) {
  modelForm.value = {
    id: model.id,
    name: model.name,
    provider: model.provider,
    baseUrl: model.baseUrl,
    model: model.model,
    apiKey: '',
    proxyHost: model.proxyHost || '',
    proxyPort: model.proxyPort || '',
    enabled: model.enabled,
    active: model.active,
    supportsTools: model.supportsTools !== false,
    supportsStreaming: model.supportsStreaming !== false,
    supportsVision: model.supportsVision === true,
    contextWindow: model.contextWindow || 0,
    temperature: model.temperature ?? '',
    topP: model.topP ?? '',
    maxTokens: model.maxTokens ?? '',
    frequencyPenalty: model.frequencyPenalty ?? '',
    presencePenalty: model.presencePenalty ?? '',
    timeoutSeconds: model.timeoutSeconds || 120,
    requestOptionsJson: model.requestOptionsJson || '',
    fallbackModelId: model.fallbackModelId || '',
    failoverPolicy: model.failoverPolicy || 'any_failure',
    clearApiKey: false,
    inputPricePerMillionTokens: model.inputPricePerMillionTokens ?? '',
    outputPricePerMillionTokens: model.outputPricePerMillionTokens ?? ''
  }
  modelFormError.value = ''
  modelCatalog.value = []
  modelCatalogError.value = ''
  modelCatalogSelection.value = []
}

async function fetchModelCatalog() {
  modelCatalogError.value = ''
  modelCatalogLoading.value = true
  try {
    const editing = Boolean(modelForm.value.id)
    const response = await fetch(editing
      ? `/api/v1/models/${encodeURIComponent(modelForm.value.id)}/catalog`
      : '/api/v1/models/catalog', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(editing
        ? { apiKey: modelForm.value.apiKey.trim() || apiKey.value.trim() || null }
        : {
            provider: modelForm.value.provider.trim(),
            baseUrl: modelForm.value.baseUrl.trim(),
            apiKey: modelForm.value.apiKey.trim() || apiKey.value.trim() || null,
            proxyHost: modelForm.value.proxyHost.trim() || null,
            proxyPort: Number(modelForm.value.proxyPort) || 0,
            timeoutSeconds: Number(modelForm.value.timeoutSeconds) || 120
          })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || '模型目录获取失败')
    modelCatalog.value = payload
    modelCatalogSelection.value = payload.map((entry) => entry.id)
    if (payload.length === 0) modelCatalogError.value = '供应商没有返回可用模型'
  } catch (requestError) {
    modelCatalog.value = []
    modelCatalogError.value = requestError.message
  } finally {
    modelCatalogLoading.value = false
  }
}

function selectCatalogModel(entry) {
  modelForm.value.model = entry.id
  if (!modelForm.value.name.trim()) modelForm.value.name = entry.id
}

function buildModelRequest(modelId, name) {
  return {
    name,
    provider: modelForm.value.provider.trim(),
    baseUrl: modelForm.value.baseUrl.trim(),
    model: modelId,
    proxyHost: modelForm.value.proxyHost.trim() || null,
    proxyPort: Number(modelForm.value.proxyPort) || 0,
    enabled: modelForm.value.enabled,
    active: false,
    supportsTools: modelForm.value.supportsTools,
    supportsStreaming: modelForm.value.supportsStreaming,
    supportsVision: modelForm.value.supportsVision,
    contextWindow: Number(modelForm.value.contextWindow) || 0,
    temperature: modelForm.value.temperature === '' ? null : Number(modelForm.value.temperature),
    topP: modelForm.value.topP === '' ? null : Number(modelForm.value.topP),
    maxTokens: modelForm.value.maxTokens === '' ? null : Number(modelForm.value.maxTokens),
    frequencyPenalty: modelForm.value.frequencyPenalty === '' ? null : Number(modelForm.value.frequencyPenalty),
    presencePenalty: modelForm.value.presencePenalty === '' ? null : Number(modelForm.value.presencePenalty),
    timeoutSeconds: Number(modelForm.value.timeoutSeconds) || 120,
    requestOptionsJson: modelForm.value.requestOptionsJson.trim() || null,
    fallbackModelId: null,
    failoverPolicy: modelForm.value.failoverPolicy,
    inputPricePerMillionTokens: modelForm.value.inputPricePerMillionTokens === '' ? null : Number(modelForm.value.inputPricePerMillionTokens),
    outputPricePerMillionTokens: modelForm.value.outputPricePerMillionTokens === '' ? null : Number(modelForm.value.outputPricePerMillionTokens),
    ...(modelForm.value.apiKey.trim() ? { apiKey: modelForm.value.apiKey.trim() } : {})
  }
}

async function addSelectedCatalogModels() {
  modelCatalogError.value = ''
  const selected = modelCatalogSelection.value
  if (selected.length === 0) {
    modelCatalogError.value = '请选择至少一个模型'
    return
  }
  const provider = modelForm.value.provider.trim()
  const existing = new Set(models.value
    .filter((model) => model.provider === provider)
    .map((model) => model.model))
  const ids = selected.filter((id) => !existing.has(id))
  if (ids.length === 0) {
    modelCatalogError.value = '所选模型已经存在于当前供应商配置中'
    return
  }
  modelCatalogSaving.value = true
  try {
    for (const id of ids) {
      const name = ids.length === 1 && modelForm.value.name.trim()
        ? modelForm.value.name.trim()
        : `${provider} · ${id}`
      const response = await fetch('/api/v1/models', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildModelRequest(id, name))
      })
      const payload = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(payload.message || payload.error || `模型 ${id} 保存失败`)
    }
    modelCatalog.value = modelCatalog.value.filter((entry) => !ids.includes(entry.id))
    modelCatalogSelection.value = []
    await refreshModels()
  } catch (requestError) {
    modelCatalogError.value = requestError.message
  } finally {
    modelCatalogSaving.value = false
  }
}

async function saveModel() {
  modelFormError.value = ''
  if (!modelForm.value.name.trim() || !modelForm.value.provider.trim() || !modelForm.value.baseUrl.trim() || !modelForm.value.model.trim()) {
    modelFormError.value = '请填写名称、供应商、Base URL 和模型名'
    return
  }
  modelSaving.value = true
  try {
    const editing = Boolean(modelForm.value.id)
    const response = await fetch(editing ? `/api/v1/models/${encodeURIComponent(modelForm.value.id)}` : '/api/v1/models', {
      method: editing ? 'PATCH' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: modelForm.value.name.trim(),
        provider: modelForm.value.provider.trim(),
        baseUrl: modelForm.value.baseUrl.trim(),
        model: modelForm.value.model.trim(),
        proxyHost: modelForm.value.proxyHost.trim() || null,
        proxyPort: Number(modelForm.value.proxyPort) || 0,
        enabled: modelForm.value.enabled,
        active: modelForm.value.active,
        supportsTools: modelForm.value.supportsTools,
        supportsStreaming: modelForm.value.supportsStreaming,
        supportsVision: modelForm.value.supportsVision,
        contextWindow: Number(modelForm.value.contextWindow) || 0,
        temperature: modelForm.value.temperature === '' ? null : Number(modelForm.value.temperature),
        topP: modelForm.value.topP === '' ? null : Number(modelForm.value.topP),
        maxTokens: modelForm.value.maxTokens === '' ? null : Number(modelForm.value.maxTokens),
        frequencyPenalty: modelForm.value.frequencyPenalty === '' ? null : Number(modelForm.value.frequencyPenalty),
        presencePenalty: modelForm.value.presencePenalty === '' ? null : Number(modelForm.value.presencePenalty),
        timeoutSeconds: Number(modelForm.value.timeoutSeconds) || 120,
        requestOptionsJson: modelForm.value.requestOptionsJson.trim() || null,
        fallbackModelId: modelForm.value.fallbackModelId || null,
        failoverPolicy: modelForm.value.failoverPolicy,
        inputPricePerMillionTokens: modelForm.value.inputPricePerMillionTokens === '' ? null : Number(modelForm.value.inputPricePerMillionTokens),
        outputPricePerMillionTokens: modelForm.value.outputPricePerMillionTokens === '' ? null : Number(modelForm.value.outputPricePerMillionTokens),
        ...(editing && !modelForm.value.apiKey.trim() && !modelForm.value.clearApiKey
          ? {}
          : { apiKey: modelForm.value.apiKey.trim() || null })
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || '模型保存失败')
    if (editing) {
      const index = models.value.findIndex((model) => model.id === payload.id)
      if (index >= 0) models.value[index] = payload
    } else {
      models.value.push(payload)
    }
    if (payload.active && payload.enabled) selectedModelId.value = payload.id
    resetModelForm()
    await refreshModels()
  } catch (requestError) {
    modelFormError.value = requestError.message
  } finally {
    modelSaving.value = false
  }
}

async function activateModel(model) {
  const response = await fetch(`/api/v1/models/${encodeURIComponent(model.id)}/activate`, { method: 'POST' })
  if (!response.ok) return
  selectedModelId.value = model.id
  await refreshModels()
}

async function testModel(model) {
  modelTesting.value = model.id
  try {
    const response = await fetch(`/api/v1/models/${encodeURIComponent(model.id)}/test`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey: apiKey.value.trim() || null })
    })
    const payload = await response.json().catch(() => ({}))
    model.lastTest = payload
    await refreshModelHealth(model)
  } catch (requestError) {
    model.lastTest = { ok: false, message: requestError.message }
  } finally {
    modelTesting.value = null
  }
}

async function refreshModelHealth(model) {
  try {
    const response = await fetch(`/api/v1/models/${encodeURIComponent(model.id)}/health`)
    model.health = response.ok ? await response.json() : null
  } catch {
    model.health = null
  }
}

async function toggleModel(model) {
  const response = await fetch(`/api/v1/models/${encodeURIComponent(model.id)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: !model.enabled })
  })
  if (!response.ok) return
  await refreshModels()
}

async function deleteModel(model) {
  const response = await fetch(`/api/v1/models/${encodeURIComponent(model.id)}`, { method: 'DELETE' })
  if (!response.ok) return
  if (selectedModelId.value === model.id) selectedModelId.value = null
  await refreshModels()
  resetModelForm()
}

async function sendMessage() {
  if (!canSend.value) return
  const prompt = draft.value.trim()
  draft.value = ''
  error.value = ''
  messages.value.push({ role: 'user', content: prompt })
  messages.value.push({ role: 'assistant', content: '', reasoningContent: '' })
  const assistantMessage = messages.value[messages.value.length - 1]
  trace.value = []
  sending.value = true
  await scrollTranscript()

  try {
    const response = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify({
        message: prompt,
        apiKey: apiKey.value.trim() || null,
        conversationId: conversationId.value,
        modelId: selectedModelId.value,
        agentId: selectedAgentId.value,
        mode: selectedMode.value
      })
    })
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}))
      throw new Error(payload.error || '请求失败')
    }

    await consumeSse(response, (event, data) => {
      if (event === 'delta') {
        assistantMessage.content += typeof data === 'string' ? data : ''
        void scrollTranscript()
      } else if (event === 'reasoning_delta') {
        assistantMessage.reasoningContent += typeof data === 'string' ? data : ''
        void scrollTranscript()
      } else if (event === 'tool_call') {
        const toolMessage = {
          role: 'tool',
          id: data.id,
          name: data.name,
          arguments: data.arguments,
          result: null,
          state: 'running',
          runId: null
        }
        const assistantPosition = messages.value.indexOf(assistantMessage)
        messages.value.splice(assistantPosition, 0, toolMessage)
        trace.value.push({ type: 'tool', name: data.name, arguments: data.arguments, result: null })
        void scrollTranscript()
      } else if (event === 'tool_result') {
        const pending = [...trace.value].reverse().find((item) => item.type === 'tool' && item.name === data.name && !item.result)
        if (pending) pending.result = data.result
        else trace.value.push(data)
        const pendingMessage = [...messages.value].reverse().find((item) => item.role === 'tool' && item.name === data.name && item.result === null)
        if (pendingMessage) {
          pendingMessage.result = data.result
          pendingMessage.state = 'complete'
        }
        void scrollTranscript()
      } else if (event === 'done') {
        conversationId.value = data.conversationId || conversationId.value
        void refreshConversations()
        void refreshContext()
        assistantMessage.content = data.answer || assistantMessage.content
        assistantMessage.runId = data.runId
        trace.value = data.trace || trace.value
        const finalModelTrace = [...trace.value].reverse().find((item) => item.type === 'model')
        assistantMessage.reasoningContent = finalModelTrace?.reasoningContent || assistantMessage.reasoningContent
        syncToolMessages(trace.value)
        const pendingTool = [...messages.value].reverse().find((item) => item.role === 'tool' && item.result === null)
        if (data.pendingApproval) {
          if (pendingTool) {
            pendingTool.runId = data.runId
            pendingTool.state = 'awaiting_approval'
            pendingTool.approval = data.pendingApproval
          } else {
            const position = messages.value.indexOf(assistantMessage)
            messages.value.splice(position < 0 ? messages.value.length : position, 0, {
              role: 'tool',
              id: data.pendingApproval.toolCallId,
              name: data.pendingApproval.toolName,
              arguments: data.pendingApproval.arguments,
              result: null,
              state: 'awaiting_approval',
              runId: data.runId,
              approval: data.pendingApproval
            })
          }
        }
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
    const assistantPosition = messages.value.indexOf(assistantMessage)
    if (assistantPosition >= 0) messages.value.splice(assistantPosition, 1)
    messages.value.push({ role: 'error', content: requestError.message })
  } finally {
    sending.value = false
    await scrollTranscript()
  }
}

async function approveTool(toolMessage, approved) {
  if (!toolMessage.runId || toolMessage.state === 'approving') return
  toolMessage.state = 'approving'
  error.value = ''
  try {
    const response = await fetch(`/api/v1/runs/${encodeURIComponent(toolMessage.runId)}/approval`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ approved, apiKey: apiKey.value.trim() || null })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.error || '审批操作失败')
    const completedTool = [...(payload.trace || [])].reverse().find((item) => item.type === 'tool' && item.name === toolMessage.name)
    toolMessage.result = completedTool?.result || (approved ? 'Tool approved' : 'Tool execution denied by user')
    toolMessage.state = 'complete'
    trace.value = payload.trace || trace.value
    const assistantMessage = [...messages.value].reverse().find((item) => item.role === 'assistant' && item.runId === payload.runId)
    if (assistantMessage) assistantMessage.content = payload.message || assistantMessage.content
    if (payload.pendingApproval) {
      const nextTool = {
        role: 'tool',
        id: payload.pendingApproval.toolCallId,
        name: payload.pendingApproval.toolName,
        arguments: payload.pendingApproval.arguments,
        result: null,
        state: 'awaiting_approval',
        runId: payload.runId,
        approval: payload.pendingApproval
      }
      const position = assistantMessage ? messages.value.indexOf(assistantMessage) : messages.value.length
      messages.value.splice(position, 0, nextTool)
    } else if (assistantMessage) {
      history.value.unshift({
        prompt: 'Approved tool continuation',
        answer: payload.message,
        turns: payload.turns || 0,
        tools: (payload.trace || []).filter((item) => item.type === 'tool').length,
        time: new Date()
      })
      await refreshContext()
    }
  } catch (requestError) {
    toolMessage.state = 'awaiting_approval'
    error.value = requestError.message
  } finally {
    await scrollTranscript()
  }
}

async function consumeSse(response, onEvent) {
  if (!response.body) throw new Error('流式响应不可用')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const blocks = buffer.split(/\r\n\r\n|\n\n|\r\r/)
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      const parsed = parseSseBlock(block)
      if (parsed) {
        onEvent(parsed.event, parsed.data)
        await new Promise((resolve) => setTimeout(resolve, 0))
      }
    }
    if (done) break
  }
  buffer += decoder.decode()
  const trailing = parseSseBlock(buffer.trim())
  if (trailing) onEvent(trailing.event, trailing.data)
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

async function refreshConversations() {
  try {
    const response = await fetch('/api/v1/conversations?limit=50')
    if (!response.ok) throw new Error('Conversation list unavailable')
    conversations.value = await response.json()
  } catch (requestError) {
    conversationActionError.value = requestError.message
  }
}

async function selectConversation(conversation) {
  if (!conversation?.id || sending.value || conversationActionLoading.value) return
  conversationId.value = conversation.id
  conversationListResults.value = []
  conversationListQuery.value = ''
  trace.value = []
  error.value = ''
  await replayConversation()
}

async function searchAllConversations() {
  const query = conversationListQuery.value.trim()
  if (!query || conversationActionLoading.value) return
  conversationActionLoading.value = true
  conversationActionError.value = ''
  try {
    const params = new URLSearchParams({ query, limit: '30' })
    const response = await fetch(`/api/v1/conversations/search?${params}`)
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || 'Conversation search failed')
    conversationListResults.value = payload
  } catch (requestError) {
    conversationActionError.value = requestError.message
  } finally {
    conversationActionLoading.value = false
  }
}

async function renameConversation(conversation) {
  if (!conversation?.id || conversationActionLoading.value) return
  const title = window.prompt('Conversation title', conversation.title)
  if (title === null || !title.trim()) return
  conversationActionLoading.value = true
  conversationActionError.value = ''
  try {
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversation.id)}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: title.trim() })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Conversation rename failed')
    const index = conversations.value.findIndex((item) => item.id === conversation.id)
    if (index >= 0) conversations.value[index] = payload
    conversationListResults.value = conversationListResults.value.map((item) =>
      item.conversationId === conversation.id ? { ...item, conversationTitle: payload.title } : item)
  } catch (requestError) {
    conversationActionError.value = requestError.message
  } finally {
    conversationActionLoading.value = false
  }
}

async function deleteConversation(conversation) {
  if (!conversation?.id || conversationActionLoading.value) return
  if (!window.confirm(`Delete conversation "${conversation.title}"?`)) return
  conversationActionLoading.value = true
  conversationActionError.value = ''
  try {
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversation.id)}`, { method: 'DELETE' })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Conversation delete failed')
    conversations.value = conversations.value.filter((item) => item.id !== conversation.id)
    conversationListResults.value = conversationListResults.value.filter((item) => item.conversationId !== conversation.id)
    if (conversationId.value === conversation.id) clearConversation()
  } catch (requestError) {
    conversationActionError.value = requestError.message
  } finally {
    conversationActionLoading.value = false
  }
}

function clearConversation() {
  messages.value = []
  trace.value = []
  error.value = ''
  conversationId.value = null
  contextInfo.value = null
  contextError.value = ''
  conversationSearchQuery.value = ''
  conversationSearchResults.value = []
  conversationListResults.value = []
  conversationActionError.value = ''
}

function replayMessageList(items) {
  return (items || []).map((item) => {
    const role = String(item.role || '').toLowerCase()
    if (role === 'tool') {
      return { role: 'tool', id: item.toolCallId || null, name: 'tool', arguments: null, result: item.content || '', state: 'complete', runId: null }
    }
    if (role === 'user') return { role: 'user', content: item.content || '' }
    return { role: 'assistant', content: item.content || '', reasoningContent: item.reasoningContent || '' }
  })
}

async function replayConversation() {
  if (!conversationId.value || conversationActionLoading.value) return
  conversationActionLoading.value = true
  conversationActionError.value = ''
  try {
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversationId.value)}/messages`)
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || 'Conversation replay failed')
    messages.value = replayMessageList(payload)
    trace.value = []
    conversationSearchResults.value = []
    await Promise.all([refreshContext(), refreshMemories()])
    await scrollTranscript()
  } catch (requestError) {
    conversationActionError.value = requestError.message
  } finally {
    conversationActionLoading.value = false
  }
}

async function forkConversation() {
  if (!conversationId.value || conversationActionLoading.value) return
  conversationActionLoading.value = true
  conversationActionError.value = ''
  try {
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversationId.value)}/fork`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: `Fork ${new Date().toLocaleString('zh-CN')}` })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Conversation fork failed')
    conversationId.value = payload.conversationId
    void refreshConversations()
    conversationActionLoading.value = false
    await replayConversation()
  } catch (requestError) {
    conversationActionError.value = requestError.message
  } finally {
    conversationActionLoading.value = false
  }
}

async function searchConversation() {
  if (!conversationId.value || !conversationSearchQuery.value.trim() || conversationActionLoading.value) return
  conversationActionLoading.value = true
  conversationActionError.value = ''
  try {
    const params = new URLSearchParams({ query: conversationSearchQuery.value.trim(), limit: '20' })
    const response = await fetch(`/api/v1/conversations/${encodeURIComponent(conversationId.value)}/search?${params}`)
    const payload = await response.json().catch(() => [])
    if (!response.ok) throw new Error(payload.message || payload.error || 'Conversation search failed')
    conversationSearchResults.value = payload
  } catch (requestError) {
    conversationActionError.value = requestError.message
  } finally {
    conversationActionLoading.value = false
  }
}

function formatArguments(argumentsNode) {
  if (!argumentsNode) return '{}'
  return JSON.stringify(argumentsNode, null, 2)
}

function syncToolMessages(events) {
  const toolEvents = (events || []).filter((item) => item?.type === 'tool')
  const existing = messages.value.filter((item) => item.role === 'tool')
  const occurrences = new Map()

  for (const event of toolEvents) {
    const occurrence = occurrences.get(event.name) || 0
    occurrences.set(event.name, occurrence + 1)
    const candidates = existing.filter((item) => item.name === event.name)
    let message = candidates[occurrence]
    if (!message) {
      message = {
        role: 'tool',
        id: null,
        name: event.name,
        arguments: event.arguments,
        result: event.result ?? null,
        state: event.result == null ? 'running' : 'complete',
        runId: null
      }
      const assistantPosition = messages.value.indexOf([...messages.value].reverse().find((item) => item.role === 'assistant'))
      messages.value.splice(assistantPosition < 0 ? messages.value.length : assistantPosition, 0, message)
      existing.push(message)
    } else {
      message.arguments = event.arguments || message.arguments
      message.result = event.result ?? message.result
      if (event.result != null) message.state = 'complete'
    }
  }
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

onMounted(() => {
  refreshHealth()
  refreshTools()
  refreshMcpServers()
  refreshSkills()
  refreshModels()
  refreshWorkspaces()
  refreshConversations()
  refreshAgents()
  refreshSubAgents()
  refreshMemories()
  refreshContext()
  refreshContextProviders()
  refreshPlans()
})

onUnmounted(() => {
  clearTimeout(planPollTimer)
  clearTimeout(subAgentRunPollTimer)
  closePlanEventStream()
  closeSubAgentEventStream()
})
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
        <div class="eyebrow">运行时</div>
        <div class="runtime-row">
          <span class="status-dot" :class="{ online: runtime.runtimeStarted }"></span>
          <strong>{{ runtime.runtimeStarted ? '已连接' : '未连接' }}</strong>
          <span class="runtime-port">:{{ apiPort }}</span>
        </div>
        <div class="runtime-meta">已注册 {{ runtime.pluginCount }} 个插件</div>
        <div class="runtime-persistence" :class="{ enabled: runtime.persistenceEnabled }">
          <span class="persistence-dot"></span>
          MariaDB 持久化 {{ runtime.persistenceEnabled ? '已开启' : '未开启' }}
        </div>
        <div class="runtime-plugin-actions">
          <button class="secondary-button compact runtime-plugin-button" type="button" :disabled="pluginLoading" @click="loadPlugins">{{ pluginLoading ? '加载中' : '加载插件' }}</button>
          <button v-if="runtime.pluginCount" class="secondary-button compact runtime-plugin-button" type="button" :disabled="pluginLoading" title="卸载动态插件" @click="unloadPlugins">卸载</button>
        </div>
      </div>

      <button class="new-run-button" type="button" @click="clearConversation">
        <span class="button-icon">+</span>
        新建会话
      </button>

      <div class="sidebar-section conversation-list-section">
        <div class="section-label conversation-list-heading">
          <span>会话</span>
          <button class="icon-button compact-icon" type="button" title="刷新会话" aria-label="刷新会话" @click="refreshConversations">↻</button>
        </div>
        <form class="conversation-list-search" @submit.prevent="searchAllConversations">
          <input v-model="conversationListQuery" type="search" placeholder="搜索全部会话" :disabled="conversationActionLoading" />
          <button class="icon-button compact-icon" type="submit" title="搜索全部会话" aria-label="搜索全部会话" :disabled="conversationActionLoading || !conversationListQuery.trim()">⌕</button>
        </form>
        <div v-if="conversationListResults.length" class="conversation-list-results">
          <button v-for="result in conversationListResults" :key="`${result.conversationId}-${result.messageIndex}`" class="conversation-list-result" type="button" @click="selectConversation({ id: result.conversationId })">
            <strong>{{ result.conversationTitle || result.conversationId }}</strong>
            <span>{{ result.role }} · {{ result.content }}</span>
          </button>
        </div>
        <p v-else-if="conversations.length === 0" class="empty-history">暂无会话</p>
        <div v-else class="conversation-list">
          <div v-for="conversation in conversations" :key="conversation.id" class="conversation-list-item" :class="{ selected: conversation.id === conversationId }">
            <button class="conversation-list-select" type="button" :disabled="conversationActionLoading" @click="selectConversation(conversation)">
              <strong>{{ conversation.title }}</strong>
              <span>{{ conversation.messageCount }} 条消息 · {{ formatTime(new Date(conversation.updatedAt)) }}</span>
            </button>
            <button class="icon-button compact-icon conversation-list-action" type="button" title="重命名会话" aria-label="重命名会话" :disabled="conversationActionLoading" @click="renameConversation(conversation)">✎</button>
            <button class="icon-button compact-icon conversation-list-action danger" type="button" title="删除会话" aria-label="删除会话" :disabled="conversationActionLoading" @click="deleteConversation(conversation)">×</button>
          </div>
        </div>
      </div>

      <div class="sidebar-section">
        <div class="section-label">最近运行</div>
        <div v-if="history.length === 0" class="empty-history">暂无运行记录</div>
        <button v-for="item in history" :key="item.time.getTime()" class="history-item" type="button">
          <span class="history-title">{{ item.prompt }}</span>
          <span class="history-meta">{{ item.tools }} 个工具 · {{ formatTime(item.time) }}</span>
        </button>
      </div>

      <div class="sidebar-footer">
        <span class="footer-label">MODEL</span>
        <span class="model-name">{{ models.find((item) => item.id === selectedModelId)?.model || '未选择模型' }}</span>
        <span class="version">v0.1.0</span>
      </div>
    </aside>

    <section class="workspace">
      <header class="workspace-header">
        <div>
          <div class="eyebrow">对话调试台</div>
          <h1>Agent Playground</h1>
        </div>
        <div class="header-actions">
          <span class="trace-summary">{{ modelCount }} Model · {{ toolCount }} Tools</span>
          <button class="tools-button" type="button" title="管理 Tools" @click="openToolManager">
            <span>Tools</span>
            <span class="tools-button-count">{{ tools.length }}</span>
          </button>
          <button class="tools-button" type="button" title="管理 MCP Servers" @click="openCapabilities('mcp')">
            <span>MCP</span>
            <span class="tools-button-count">{{ mcpServers.filter((item) => item.status === 'CONNECTED').length }}</span>
          </button>
          <button class="tools-button" type="button" title="管理 Skills" @click="openCapabilities('skills')">
            <span>Skills</span>
            <span class="tools-button-count">{{ skills.filter((item) => item.enabled).length }}</span>
          </button>
          <select v-model="selectedModelId" class="model-picker" title="Select model" :disabled="sending">
            <option v-for="model in models.filter((item) => item.enabled)" :key="model.id" :value="model.id">{{ model.name }} · {{ model.model }}</option>
            <option v-if="models.filter((item) => item.enabled).length === 0" :value="null">未选择模型</option>
          </select>
          <select v-model="selectedAgentId" class="model-picker" title="Select agent profile" :disabled="sending" @change="applyAgentSelection">
            <option v-for="agent in agents.filter((item) => item.enabled)" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
            <option v-if="agents.filter((item) => item.enabled).length === 0" :value="null">未选择 Agent</option>
          </select>
          <select v-model="selectedMode" class="mode-picker" title="Select run mode" :disabled="sending">
            <option value="chat">Chat</option>
            <option value="planning">Planning</option>
            <option value="execution">Execution</option>
          </select>
          <button class="tools-button" type="button" title="管理 Models" @click="openCapabilities('models')">
            <span>Models</span>
            <span class="tools-button-count">{{ models.length }}</span>
          </button>
          <button class="tools-button" type="button" title="管理 Plans" @click="openCapabilities('plans')">
            <span>Plans</span>
            <span class="tools-button-count">{{ plans.length }}</span>
          </button>
          <label class="api-key-control">
            <span>调试 API KEY</span>
            <input
              v-model="apiKey"
              type="password"
              autocomplete="off"
              spellcheck="false"
              placeholder="可选"
              :disabled="sending"
            />
          </label>
          <button class="icon-button" type="button" title="清空当前会话" aria-label="清空当前会话" @click="clearConversation">⌫</button>
        </div>
      </header>

      <div class="conversation-toolbar">
        <span class="conversation-id" :title="conversationId || '下一次运行时会创建新会话'">
          {{ conversationId ? `conversation ${conversationId}` : '新会话' }}
        </span>
        <button class="secondary-button compact" type="button" title="重放当前会话" :disabled="!conversationId || conversationActionLoading" @click="replayConversation">重放</button>
        <button class="secondary-button compact" type="button" title="Fork current conversation" :disabled="!conversationId || conversationActionLoading" @click="forkConversation">Fork</button>
        <form class="conversation-search" @submit.prevent="searchConversation">
          <input v-model="conversationSearchQuery" type="search" placeholder="搜索当前会话" :disabled="!conversationId || conversationActionLoading" />
          <button class="icon-button compact-icon" type="submit" title="搜索当前会话" aria-label="搜索当前会话" :disabled="!conversationId || conversationActionLoading || !conversationSearchQuery.trim()">⌕</button>
        </form>
        <span v-if="conversationActionError" class="conversation-action-error">{{ conversationActionError }}</span>
      </div>
      <div v-if="conversationSearchResults.length" class="conversation-search-results">
        <button v-for="result in conversationSearchResults" :key="`${result.messageIndex}-${result.content}`" type="button" class="conversation-search-result" @click="conversationSearchResults = []">
          <span>{{ result.messageIndex + 1 }} · {{ result.role }}</span>
          <strong>{{ result.content }}</strong>
        </button>
      </div>

      <div ref="transcript" class="transcript">
        <div v-if="messages.length === 0" class="empty-state">
          <div class="empty-glyph">↗</div>
          <h2>开始一次 Agent 运行</h2>
          <p>输入任务，并在右侧查看每一轮 Model 与 Tool 调用。</p>
        </div>

        <article v-for="(item, index) in messages" :key="`${item.role}-${index}`" class="message-row" :class="[item.role, { pending: item.role === 'assistant' && !item.content && !item.reasoningContent, streaming: item.role === 'assistant' && sending }]">
          <div class="message-avatar">{{ item.role === 'user' ? 'S' : item.role === 'error' ? '!' : item.role === 'tool' ? 'T' : 'D' }}</div>
          <div class="message-body">
            <div class="message-meta">
              <strong>{{ item.role === 'user' ? '你' : item.role === 'error' ? '运行时' : item.role === 'tool' ? 'Tool 执行' : 'DSH Agent' }}</strong>
              <span>{{ item.role === 'user' ? '提问' : item.role === 'error' ? '错误' : item.role === 'tool' ? item.state : '回答' }}</span>
            </div>
            <div v-if="item.role === 'assistant'" class="assistant-message-content">
              <details v-if="item.reasoningContent" class="reasoning-block" :open="sending">
                <summary>思考过程</summary>
                <div class="message-content markdown-content reasoning-content" v-html="renderMarkdown(item.reasoningContent)"></div>
              </details>
              <div class="message-content markdown-content" v-html="renderMarkdown(item.content)"></div>
            </div>
            <div v-else-if="item.role === 'tool'" class="tool-message-content">
              <div class="tool-message-title"><strong>{{ item.name }}</strong><span>{{ item.state === 'running' ? '运行中' : item.state === 'awaiting_approval' || item.state === 'approving' ? '需要审批' : '已完成' }}</span></div>
              <div class="tool-message-label">INPUT</div>
              <pre>{{ formatArguments(item.arguments) }}</pre>
              <template v-if="item.result !== null">
                <div class="tool-message-label">OUTPUT</div>
                <pre class="result">{{ item.result }}</pre>
              </template>
              <div v-if="item.state === 'awaiting_approval'" class="tool-approval-actions">
                <button class="secondary-button compact" type="button" @click="approveTool(item, true)">批准</button>
                <button class="secondary-button compact" type="button" @click="approveTool(item, false)">拒绝</button>
              </div>
            </div>
            <div v-else class="message-content">{{ item.content }}</div>
          </div>
        </article>

        <div v-if="sending" class="thinking-row">
          <span class="thinking-dots"><i></i><i></i><i></i></span>
          Agent 正在执行…
        </div>
      </div>

      <form class="composer" @submit.prevent="sendMessage">
        <textarea
          v-model="draft"
          rows="3"
          placeholder="输入任务或问题…"
          :disabled="sending"
          @keydown="handleComposerKeydown"
        ></textarea>
        <div class="composer-footer">
          <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
          <button class="send-button" type="submit" :disabled="!canSend">
            <span>{{ sending ? '执行中' : '运行 Agent' }}</span>
            <span class="send-arrow">↗</span>
          </button>
        </div>
      </form>
    </section>

    <aside class="trace-panel">
      <header class="trace-header">
        <div>
          <div class="eyebrow">可观测性</div>
          <h2>Run Trace</h2>
        </div>
        <span class="trace-count">{{ trace.length }}</span>
      </header>

      <div v-if="trace.length === 0" class="trace-empty">
        <div class="trace-empty-line"></div>
        <p>运行后，这里会显示 Tool 调用和 Model 轮次。</p>
      </div>

      <div v-else class="trace-list">
        <article v-for="(event, index) in trace" :key="`${event.type}-${index}`" class="trace-event" :class="event.type">
          <div class="trace-marker">{{ event.type === 'tool' ? 'T' : 'M' }}</div>
          <div class="trace-event-body">
            <div class="trace-event-head">
              <strong>{{ event.type === 'tool' ? event.name : 'Model response' }}</strong>
              <span>#{{ index + 1 }}</span>
            </div>
            <div v-if="event.type === 'model'" class="trace-content">
              <details v-if="event.reasoningContent" class="reasoning-block trace-reasoning">
                <summary>Thinking</summary>
                <div class="markdown-content" v-html="renderMarkdown(event.reasoningContent)"></div>
              </details>
              <div class="markdown-content" v-html="renderMarkdown(event.content)"></div>
              <small v-if="event.totalTokens != null">{{ event.promptTokens ?? '?' }} in · {{ event.completionTokens ?? '?' }} out · {{ event.totalTokens }} total tokens</small>
            </div>
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

  <div v-if="toolManagerOpen" class="modal-backdrop" @click.self="toolManagerOpen = false">
    <section class="tool-manager" role="dialog" aria-modal="true" aria-labelledby="tool-manager-title">
      <header class="tool-manager-header">
        <div>
          <div class="eyebrow">RUNTIME REGISTRY</div>
          <h2 id="tool-manager-title">Tool manager</h2>
        </div>
        <button class="icon-button" type="button" title="Close tool manager" aria-label="Close tool manager" @click="toolManagerOpen = false">×</button>
      </header>

      <nav class="capability-tabs" aria-label="Capabilities">
        <button :class="{ active: capabilityTab === 'tools' }" type="button" @click="capabilityTab = 'tools'">Tools</button>
        <button :class="{ active: capabilityTab === 'mcp' }" type="button" @click="capabilityTab = 'mcp'">MCP Servers</button>
        <button :class="{ active: capabilityTab === 'skills' }" type="button" @click="capabilityTab = 'skills'">Skills</button>
        <button :class="{ active: capabilityTab === 'models' }" type="button" @click="capabilityTab = 'models'">Models</button>
        <button :class="{ active: capabilityTab === 'workspaces' }" type="button" @click="capabilityTab = 'workspaces'; refreshWorkspaces()">Workspaces</button>
        <button :class="{ active: capabilityTab === 'agents' }" type="button" @click="capabilityTab = 'agents'">Agents</button>
        <button :class="{ active: capabilityTab === 'sub-agents' }" type="button" @click="capabilityTab = 'sub-agents'">Sub-agents</button>
        <button :class="{ active: capabilityTab === 'memory' }" type="button" @click="capabilityTab = 'memory'">Memory</button>
        <button :class="{ active: capabilityTab === 'context' }" type="button" @click="capabilityTab = 'context'; refreshContext()">Context</button>
        <button :class="{ active: capabilityTab === 'plans' }" type="button" @click="capabilityTab = 'plans'">Plans</button>
      </nav>

      <div v-if="capabilityTab === 'tools'" class="tool-manager-list">
        <div v-for="tool in tools" :key="tool.name" class="managed-tool">
          <div class="managed-tool-copy">
            <div class="managed-tool-title">
              <strong>{{ tool.name }}</strong>
              <span :class="['tool-source', tool.source]">{{ tool.source }}</span>
            </div>
            <p>{{ tool.description }}</p>
          </div>
          <div class="managed-tool-actions">
            <label class="tool-toggle" :title="tool.approvalRequired ? 'Disable approval requirement' : 'Require approval before execution'">
              <input type="checkbox" :checked="tool.approvalRequired" @change="toggleToolApproval(tool)" />
              <span></span>
            </label>
            <label class="tool-toggle" :title="tool.enabled ? 'Disable tool' : 'Enable tool'">
              <input type="checkbox" :checked="tool.enabled" @change="toggleTool(tool)" />
              <span></span>
            </label>
            <button v-if="tool.removable" class="delete-tool-button" type="button" title="Delete tool" aria-label="Delete tool" @click="deleteTool(tool)">×</button>
          </div>
        </div>
        <p v-if="tools.length === 0" class="tool-manager-empty">No tools registered.</p>
      </div>

      <form v-if="capabilityTab === 'tools'" class="tool-create-form" @submit.prevent="createTool">
        <div class="tool-form-heading">
          <div>
            <div class="eyebrow">CUSTOM TOOL</div>
            <h3>Add debug tool</h3>
          </div>
          <span class="tool-form-note">Fixed response</span>
        </div>
        <div class="tool-form-grid">
          <label>
            <span>Name</span>
            <input v-model="toolForm.name" placeholder="weather_lookup" autocomplete="off" />
          </label>
          <label>
            <span>Description</span>
            <input v-model="toolForm.description" placeholder="Look up the weather" autocomplete="off" />
          </label>
        </div>
        <label>
          <span>Parameters JSON Schema</span>
          <textarea v-model="toolForm.parameters" rows="5" spellcheck="false"></textarea>
        </label>
        <label>
          <span>Tool result</span>
          <textarea v-model="toolForm.result" rows="3" placeholder="Return value sent back to the model"></textarea>
        </label>
        <label class="plan-approval-toggle"><input v-model="toolForm.approvalRequired" type="checkbox" /> Require approval before execution</label>
        <p v-if="toolFormError" class="tool-form-error">{{ toolFormError }}</p>
        <div class="tool-form-footer">
          <button class="secondary-button" type="button" @click="resetToolForm">Reset</button>
          <button class="send-button" type="submit" :disabled="toolSaving">
            <span>{{ toolSaving ? 'Adding' : 'Add tool' }}</span>
            <span class="send-arrow">↗</span>
          </button>
        </div>
      </form>

      <div v-if="capabilityTab === 'mcp'" class="tool-manager-list">
        <div v-for="server in mcpServers" :key="server.id" class="managed-tool">
          <div class="managed-tool-copy">
            <div class="managed-tool-title">
              <strong>{{ server.name }}</strong>
              <span :class="['tool-source', server.status === 'CONNECTED' ? 'connected' : '']">{{ server.status }}</span>
            </div>
            <p>{{ server.transport }} · {{ server.endpoint || server.command }} · {{ server.approvalRequired ? 'approval required' : 'auto run' }}<br /><span v-if="server.health">health {{ server.health.status.toLowerCase() }} · {{ server.health.successCount }}/{{ server.health.failureCount }} · {{ server.health.lastLatencyMs == null ? 'no latency' : `${server.health.lastLatencyMs}ms` }}</span></p>
          </div>
          <div class="managed-tool-actions">
            <button v-if="server.status === 'CONNECTED'" class="secondary-button compact" type="button" @click="mcpAction(server, 'disconnect')">断开</button>
            <button v-else class="secondary-button compact" type="button" @click="mcpAction(server, 'connect')">连接</button>
            <button v-if="server.status === 'CONNECTED'" class="secondary-button compact" type="button" title="Refresh MCP tools" @click="mcpAction(server, 'refresh')">刷新</button>
            <button v-if="server.status === 'CONNECTED'" class="secondary-button compact" type="button" @click="inspectMcp(server, 'resources')">Resources</button>
            <button v-if="server.status === 'CONNECTED'" class="secondary-button compact" type="button" @click="inspectMcp(server, 'prompts')">Prompts</button>
            <label class="tool-toggle" :title="server.approvalRequired ? 'Disable approval requirement' : 'Require approval before execution'">
              <input type="checkbox" :checked="server.approvalRequired" @change="toggleMcpApproval(server)" />
            </label>
            <button class="delete-tool-button" type="button" title="Delete MCP server" aria-label="Delete MCP server" @click="deleteMcpServer(server)">×</button>
          </div>
          <div v-if="server.resources" class="mcp-inspector">
            <div v-for="resource in server.resources" :key="resource.uri" class="mcp-inspector-item">
              <span><strong>{{ resource.name || resource.uri }}</strong><small>{{ resource.mimeType || 'resource' }}</small></span>
              <button class="secondary-button compact" type="button" @click="readMcpResource(server, resource)">Read</button>
              <pre v-if="resource.content">{{ resource.content }}</pre>
            </div>
            <p v-if="server.resources.length === 0" class="tool-manager-empty">No MCP resources.</p>
          </div>
          <div v-if="server.prompts" class="mcp-inspector">
            <div v-for="prompt in server.prompts" :key="prompt.name" class="mcp-inspector-item">
              <span><strong>{{ prompt.title || prompt.name }}</strong><small>{{ prompt.argumentNames.join(', ') || 'no arguments' }}</small></span>
              <button class="secondary-button compact" type="button" @click="getMcpPrompt(server, prompt)">Load</button>
              <pre v-if="prompt.result">{{ prompt.result }}</pre>
            </div>
            <p v-if="server.prompts.length === 0" class="tool-manager-empty">No MCP prompts.</p>
          </div>
        </div>
        <p v-if="mcpServers.length === 0" class="tool-manager-empty">No MCP servers configured.</p>

        <form class="tool-create-form inline-form" @submit.prevent="createMcpServer">
          <div class="tool-form-heading">
            <div>
              <div class="eyebrow">MCP CLIENT</div>
              <h3>Add MCP server</h3>
            </div>
            <span class="tool-form-note">stdio / SSE / HTTP</span>
          </div>
          <div class="tool-form-grid">
            <label><span>Name</span><input v-model="mcpForm.name" placeholder="filesystem" autocomplete="off" /></label>
            <label><span>Transport</span><select v-model="mcpForm.transport"><option value="stdio">stdio</option><option value="sse">sse</option><option value="streamable_http">streamable_http</option></select></label>
          </div>
          <label v-if="mcpForm.transport !== 'stdio'"><span>Endpoint</span><input v-model="mcpForm.endpoint" :placeholder="mcpForm.transport === 'streamable_http' ? 'https://mcp.amap.com/mcp' : 'http://localhost:3000/sse'" autocomplete="off" /></label>
          <label v-else><span>Command</span><input v-model="mcpForm.command" placeholder="npx" autocomplete="off" /></label>
          <label><span>{{ mcpForm.transport === 'stdio' ? 'Arguments' : 'Endpoint hint' }}</span><input v-if="mcpForm.transport === 'stdio'" v-model="mcpForm.arguments" placeholder="-y @modelcontextprotocol/server-filesystem /tmp" autocomplete="off" /><input v-else :value="mcpForm.transport === 'streamable_http' ? 'Use Credential reference for API key' : 'Use the server SSE endpoint, for example /sse'" disabled /></label>
          <label><span>Credential reference</span><input v-model="mcpForm.credentialRef" placeholder="AMAP_API_KEY" autocomplete="off" /></label>
          <label><span>Headers JSON</span><textarea v-model="mcpForm.headers" rows="3" spellcheck="false" placeholder='{"X-Tenant": "demo"}'></textarea></label>
          <label><span>Environment JSON</span><textarea v-model="mcpForm.environment" rows="3" spellcheck="false" placeholder='{"API_KEY": "env:AMAP_API_KEY"}'></textarea></label>
          <label class="plan-approval-toggle"><input v-model="mcpForm.approvalRequired" type="checkbox" /> Require approval for MCP tools</label>
          <p v-if="mcpFormError" class="tool-form-error">{{ mcpFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetMcpForm">Reset</button><button class="send-button" type="submit" :disabled="mcpSaving"><span>{{ mcpSaving ? 'Adding' : 'Add server' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>

      <div v-if="capabilityTab === 'skills'" class="tool-manager-list">
        <div v-for="skill in skills" :key="skill.id" class="managed-tool skill-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title"><strong>{{ skill.name }}</strong><span class="tool-source">{{ skill.id }} · v{{ skill.version || '0.1.0' }}</span></div>
            <p>{{ skill.description }}<span v-if="skill.resources?.length"> · {{ skill.resources.length }} resource{{ skill.resources.length === 1 ? '' : 's' }}</span></p>
            <details><summary>查看 SKILL.md</summary><pre class="skill-content">{{ skill.content }}</pre></details>
          </div>
          <div class="managed-tool-actions">
            <button class="secondary-button compact" type="button" @click="editSkill(skill)">Edit</button>
            <label class="tool-toggle" :title="skill.enabled ? 'Disable skill' : 'Enable skill'"><input type="checkbox" :checked="skill.enabled" @change="toggleSkill(skill)" /><span></span></label>
            <button class="delete-tool-button" type="button" title="Uninstall skill" aria-label="Uninstall skill" @click="deleteSkill(skill)">×</button>
          </div>
        </div>
        <p v-if="skills.length === 0" class="tool-manager-empty">No skills found. Add skills/&lt;name&gt;/SKILL.md and refresh.</p>
        <div class="tool-form-footer skill-footer"><button class="secondary-button" type="button" @click="refreshSkills">Refresh skills</button></div>

        <form class="tool-create-form inline-form" @submit.prevent="saveSkill">
          <div class="tool-form-heading">
            <div>
              <div class="eyebrow">SKILL PACKAGE</div>
              <h3>{{ skillEditingId ? 'Edit skill' : 'Install skill' }}</h3>
            </div>
            <span class="tool-form-note">SKILL.md</span>
          </div>
          <div class="tool-form-grid">
            <label><span>ID</span><input v-model="skillForm.id" :disabled="Boolean(skillEditingId)" placeholder="research" autocomplete="off" /></label>
            <label><span>Name</span><input v-model="skillForm.name" placeholder="Research assistant" autocomplete="off" /></label>
          </div>
          <div class="tool-form-grid">
            <label><span>Version</span><input v-model="skillForm.version" placeholder="0.1.0" autocomplete="off" /></label>
            <label><span>Description</span><input v-model="skillForm.description" placeholder="When to use this skill" autocomplete="off" /></label>
          </div>
          <label><span>Instructions</span><textarea v-model="skillForm.content" rows="8" spellcheck="false" placeholder="Write the instructions injected into the agent system prompt."></textarea></label>
          <label class="plan-approval-toggle"><input v-model="skillForm.enabled" type="checkbox" /> Enable after install</label>
          <p v-if="skillFormError" class="tool-form-error">{{ skillFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetSkillForm">Reset</button><button class="send-button" type="submit" :disabled="skillSaving"><span>{{ skillSaving ? 'Saving' : 'Save skill' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>

      <div v-if="capabilityTab === 'models'" class="tool-manager-list">
        <div v-for="model in models" :key="model.id" class="managed-tool model-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title">
              <strong>{{ model.name }}</strong>
              <span :class="['tool-source', model.active ? 'connected' : '']">{{ model.active ? 'DEFAULT' : model.provider }}</span>
            </div>
          <p>{{ model.model }} · {{ model.baseUrl }}<br />{{ model.apiKeyConfigured ? 'API key configured' : 'Uses request API key' }} · {{ model.supportsTools ? 'tools' : 'no tools' }} · {{ model.supportsStreaming ? 'streaming' : 'non-streaming' }} · {{ model.contextWindow ? `${model.contextWindow} context` : 'context unknown' }}<br /><span v-if="model.fallbackModelId">fallback: {{ models.find((candidate) => candidate.id === model.fallbackModelId)?.name || model.fallbackModelId }} · {{ model.failoverPolicy || 'any_failure' }}</span><span v-if="model.inputPricePerMillionTokens != null || model.outputPricePerMillionTokens != null">{{ model.fallbackModelId ? ' · ' : '' }}price ${{ model.inputPricePerMillionTokens ?? '?' }} / ${{ model.outputPricePerMillionTokens ?? '?' }} per 1M tokens</span><span v-if="model.usage && model.usage.requestCount"> · usage {{ model.usage.requestCount }} requests · {{ model.usage.totalTokens }} tokens · ${{ model.usage.estimatedCostUsd.toFixed(6) }}</span><span v-if="model.health"> · health {{ model.health.status.toLowerCase() }} · {{ model.health.successCount }}/{{ model.health.failureCount }} · {{ model.health.lastLatencyMs == null ? 'no latency' : `${model.health.lastLatencyMs}ms` }}</span></p>
          </div>
          <div class="managed-tool-actions model-actions">
            <button v-if="!model.active && model.enabled" class="secondary-button compact" type="button" @click="activateModel(model)">Default</button>
            <button class="secondary-button compact" type="button" :disabled="modelTesting === model.id" @click="testModel(model)">{{ modelTesting === model.id ? 'Testing' : 'Test' }}</button>
            <button class="secondary-button compact" type="button" @click="editModel(model)">Edit</button>
            <label class="tool-toggle" :title="model.enabled ? 'Disable model' : 'Enable model'">
              <input type="checkbox" :checked="model.enabled" @change="toggleModel(model)" />
              <span></span>
            </label>
            <button v-if="models.length > 1" class="delete-tool-button" type="button" title="Delete model" aria-label="Delete model" @click="deleteModel(model)">×</button>
          </div>
          <p v-if="model.lastTest" :class="['model-health', model.lastTest.ok ? 'healthy' : 'unhealthy']">{{ model.lastTest.ok ? 'OK' : 'Failed' }} · {{ model.lastTest.message }}<span v-if="model.lastTest.content"> · {{ model.lastTest.content }}</span></p>
        </div>
        <p v-if="models.length === 0" class="tool-manager-empty">No models configured.</p>

        <form class="tool-create-form inline-form" @submit.prevent="saveModel">
          <div class="tool-form-heading">
            <div>
              <div class="eyebrow">MODEL PROFILE</div>
              <h3>{{ modelForm.id ? 'Edit model' : 'Add model' }}</h3>
            </div>
            <span class="tool-form-note">API-compatible</span>
          </div>
          <div class="tool-form-grid">
            <label><span>Name</span><input v-model="modelForm.name" placeholder="DeepSeek Production" autocomplete="off" /></label>
            <label><span>Provider</span><input v-model="modelForm.provider" list="model-provider-options" placeholder="deepseek" autocomplete="off" /><datalist id="model-provider-options"><option v-for="provider in modelProviders" :key="provider" :value="provider" /></datalist></label>
          </div>
          <label><span>Base URL</span><input v-model="modelForm.baseUrl" placeholder="https://api.deepseek.com" autocomplete="off" /></label>
          <label><span>Model</span><input v-model="modelForm.model" placeholder="deepseek-v4-flash" autocomplete="off" /></label>
          <div class="model-catalog-toolbar">
            <button class="secondary-button compact" type="button" :disabled="modelCatalogLoading" @click="fetchModelCatalog">{{ modelCatalogLoading ? 'Fetching models' : 'Fetch available models' }}</button>
            <span class="tool-form-note">Uses the form settings and temporary debug key</span>
          </div>
          <div v-if="modelCatalog.length" class="model-catalog-list">
            <div v-for="entry in modelCatalog" :key="entry.id" class="model-catalog-item">
              <label><input v-model="modelCatalogSelection" type="checkbox" :value="entry.id" /><span><strong>{{ entry.id }}</strong><small>{{ entry.ownedBy || entry.object || 'model' }}</small></span></label>
              <button class="secondary-button compact" type="button" @click="selectCatalogModel(entry)">Use</button>
            </div>
            <div class="model-catalog-actions">
              <span class="tool-form-note">{{ modelCatalogSelection.length }} selected</span>
              <button class="secondary-button compact" type="button" :disabled="modelCatalogSaving" @click="addSelectedCatalogModels">{{ modelCatalogSaving ? 'Adding models' : 'Add selected models' }}</button>
            </div>
          </div>
          <p v-if="modelCatalogError" class="tool-form-error">{{ modelCatalogError }}</p>
          <label><span>Fallback model</span><select v-model="modelForm.fallbackModelId"><option value="">No fallback</option><option v-for="candidate in models.filter((candidate) => candidate.id !== modelForm.id)" :key="candidate.id" :value="candidate.id">{{ candidate.name }} · {{ candidate.model }}</option></select></label>
          <label><span>Failover policy</span><select v-model="modelForm.failoverPolicy"><option value="any_failure">Any failure</option><option value="transient_failure">Transient failures only</option><option value="disabled">Disabled</option></select></label>
          <label><span>API Key</span><input v-model="modelForm.apiKey" type="password" autocomplete="new-password" :placeholder="modelForm.id ? 'Leave blank to keep current key' : 'Optional; request key can override'" /></label>
          <label v-if="modelForm.id" class="plan-approval-toggle"><input v-model="modelForm.clearApiKey" type="checkbox" /> Clear stored API key</label>
          <div class="tool-form-grid">
            <label><span>Proxy Host</span><input v-model="modelForm.proxyHost" placeholder="127.0.0.1" autocomplete="off" /></label>
            <label><span>Proxy Port</span><input v-model="modelForm.proxyPort" inputmode="numeric" placeholder="7897" autocomplete="off" /></label>
          </div>
          <div class="tool-form-grid model-capabilities">
            <label class="plan-approval-toggle"><input v-model="modelForm.supportsTools" type="checkbox" /> Tool calling</label>
            <label class="plan-approval-toggle"><input v-model="modelForm.supportsStreaming" type="checkbox" /> Streaming</label>
            <label class="plan-approval-toggle"><input v-model="modelForm.supportsVision" type="checkbox" /> Vision</label>
            <label><span>Context window</span><input v-model="modelForm.contextWindow" type="number" min="0" max="2000000" placeholder="0 = unknown" /></label>
          </div>
          <div class="tool-form-grid model-capabilities">
            <label><span>Temperature</span><input v-model="modelForm.temperature" type="number" min="0" max="2" step="0.01" placeholder="Provider default" /></label>
            <label><span>Top P</span><input v-model="modelForm.topP" type="number" min="0.01" max="1" step="0.01" placeholder="Provider default" /></label>
            <label><span>Max output tokens</span><input v-model="modelForm.maxTokens" type="number" min="1" max="2000000" placeholder="Provider default" /></label>
            <label><span>Request timeout (s)</span><input v-model="modelForm.timeoutSeconds" type="number" min="1" max="3600" /></label>
          </div>
          <div class="tool-form-grid model-capabilities">
            <label><span>Frequency penalty</span><input v-model="modelForm.frequencyPenalty" type="number" min="-2" max="2" step="0.01" placeholder="Provider default" /></label>
            <label><span>Presence penalty</span><input v-model="modelForm.presencePenalty" type="number" min="-2" max="2" step="0.01" placeholder="Provider default" /></label>
          </div>
          <div class="tool-form-grid model-capabilities">
            <label><span>Input price / 1M tokens (USD)</span><input v-model="modelForm.inputPricePerMillionTokens" type="number" min="0" max="1000000" step="0.000001" placeholder="Unknown" /></label>
            <label><span>Output price / 1M tokens (USD)</span><input v-model="modelForm.outputPricePerMillionTokens" type="number" min="0" max="1000000" step="0.000001" placeholder="Unknown" /></label>
          </div>
          <label><span>Provider request options JSON</span><textarea v-model="modelForm.requestOptionsJson" rows="4" spellcheck="false" placeholder='{"reasoning_effort":"high","response_format":{"type":"text"}}'></textarea></label>
          <p v-if="modelFormError" class="tool-form-error">{{ modelFormError }}</p>
          <div class="tool-form-footer">
            <button class="secondary-button" type="button" @click="resetModelForm">Reset</button>
            <button class="send-button" type="submit" :disabled="modelSaving"><span>{{ modelSaving ? 'Saving' : 'Save model' }}</span><span class="send-arrow">↗</span></button>
          </div>
        </form>
      </div>

      <div v-if="capabilityTab === 'workspaces'" class="tool-manager-list">
        <div v-for="workspace in workspaces" :key="workspace.id" class="managed-tool model-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title">
              <strong>{{ workspace.name }}</strong>
              <span :class="['tool-source', workspace.active ? 'connected' : '']">{{ workspace.active ? 'ACTIVE' : 'workspace' }}</span>
            </div>
            <p>{{ workspace.directory }}<br />{{ workspace.writeEnabled ? 'read/write' : 'read-only' }} · read {{ workspace.maxReadBytes }} B · write {{ workspace.maxWriteBytes }} B · process {{ workspace.maxProcessTimeoutSeconds }}s<br />commands: {{ (workspace.allowedCommands || []).join(', ') || 'none' }}</p>
          </div>
          <div class="managed-tool-actions model-actions">
            <button v-if="!workspace.active && workspace.enabled" class="secondary-button compact" type="button" @click="activateWorkspace(workspace)">Activate</button>
            <button class="secondary-button compact" type="button" @click="editWorkspace(workspace)">Edit</button>
            <label class="tool-toggle" :title="workspace.enabled ? 'Disable workspace' : 'Enable workspace'">
              <input type="checkbox" :checked="workspace.enabled" @change="toggleWorkspace(workspace)" />
              <span></span>
            </label>
            <button v-if="workspaces.length > 1" class="delete-tool-button" type="button" title="Delete workspace" aria-label="Delete workspace" @click="deleteWorkspace(workspace)">×</button>
          </div>
        </div>
        <p v-if="workspaces.length === 0" class="tool-manager-empty">No workspaces configured.</p>

        <form class="tool-create-form inline-form" @submit.prevent="saveWorkspace">
          <div class="tool-form-heading">
            <div>
              <div class="eyebrow">WORKSPACE PROFILE</div>
              <h3>{{ workspaceForm.id ? 'Edit workspace' : 'Add workspace' }}</h3>
            </div>
            <span class="tool-form-note">sandbox root</span>
          </div>
          <div class="tool-form-grid">
            <label><span>Name</span><input v-model="workspaceForm.name" placeholder="Project workspace" autocomplete="off" /></label>
            <label><span>Directory</span><input v-model="workspaceForm.directory" placeholder="/Users/me/IdeaProjects/project" autocomplete="off" /></label>
          </div>
          <div class="tool-form-grid">
            <label><span>Max read bytes</span><input v-model="workspaceForm.maxReadBytes" type="number" min="1" max="50000000" /></label>
            <label><span>Max write bytes</span><input v-model="workspaceForm.maxWriteBytes" type="number" min="1" max="50000000" /></label>
          </div>
          <div class="tool-form-grid">
            <label><span>Process timeout (s)</span><input v-model="workspaceForm.maxProcessTimeoutSeconds" type="number" min="1" max="3600" /></label>
            <label><span>Process output bytes</span><input v-model="workspaceForm.maxProcessOutputBytes" type="number" min="1" max="50000000" /></label>
          </div>
          <label><span>Allowlisted commands</span><input v-model="workspaceForm.allowedCommands" placeholder="git, npm, gradle" autocomplete="off" /></label>
          <div class="tool-form-grid model-capabilities">
            <label class="plan-approval-toggle"><input v-model="workspaceForm.enabled" type="checkbox" /> Enabled</label>
            <label class="plan-approval-toggle"><input v-model="workspaceForm.writeEnabled" type="checkbox" /> Allow file writes</label>
            <label class="plan-approval-toggle"><input v-model="workspaceForm.active" type="checkbox" /> Activate after save</label>
          </div>
          <p v-if="workspaceFormError" class="tool-form-error">{{ workspaceFormError }}</p>
          <div class="tool-form-footer">
            <button class="secondary-button" type="button" @click="resetWorkspaceForm">Reset</button>
            <button class="send-button" type="submit" :disabled="workspaceSaving"><span>{{ workspaceSaving ? 'Saving' : 'Save workspace' }}</span><span class="send-arrow">↗</span></button>
          </div>
        </form>
      </div>

      <div v-if="capabilityTab === 'agents'" class="tool-manager-list">
        <div v-for="agent in agents" :key="agent.id" class="managed-tool model-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title">
              <strong>{{ agent.name }}</strong>
              <span :class="['tool-source', agent.active ? 'connected' : '']">{{ agent.active ? 'DEFAULT' : String(agent.mode).toLowerCase() }}</span>
            </div>
            <p>{{ String(agent.mode).toLowerCase() }} · {{ agent.maxTurns }} turns · {{ agent.maxToolCalls ?? 64 }} tool calls · {{ agent.timeoutSeconds ?? 300 }}s · depth {{ agent.maxDepth ?? 4 }}{{ agent.modelId ? ` · ${agent.modelId}` : ' · active model' }}</p>
          </div>
          <div class="managed-tool-actions model-actions">
            <button v-if="!agent.active && agent.enabled" class="secondary-button compact" type="button" @click="activateAgent(agent)">Default</button>
            <button class="secondary-button compact" type="button" @click="editAgent(agent)">Edit</button>
            <label class="tool-toggle" :title="agent.enabled ? 'Disable agent profile' : 'Enable agent profile'">
              <input type="checkbox" :checked="agent.enabled" @change="toggleAgent(agent)" />
              <span></span>
            </label>
            <button v-if="agents.length > 1" class="delete-tool-button" type="button" title="Delete agent profile" aria-label="Delete agent profile" @click="deleteAgent(agent)">×</button>
          </div>
        </div>
        <p v-if="agents.length === 0" class="tool-manager-empty">No agent profiles configured.</p>

        <form class="tool-create-form inline-form" @submit.prevent="saveAgent">
          <div class="tool-form-heading">
            <div>
              <div class="eyebrow">AGENT PROFILE</div>
              <h3>{{ agentForm.id ? 'Edit agent' : 'Add agent' }}</h3>
            </div>
            <span class="tool-form-note">Mode-aware</span>
          </div>
          <div class="tool-form-grid">
            <label><span>Name</span><input v-model="agentForm.name" placeholder="Planning agent" autocomplete="off" /></label>
            <label><span>Mode</span><select v-model="agentForm.mode"><option value="chat">Chat</option><option value="planning">Planning</option><option value="execution">Execution</option></select></label>
          </div>
          <label><span>Model profile ID</span><input v-model="agentForm.modelId" placeholder="Leave blank to use selected model" autocomplete="off" /></label>
          <label><span>Profile instructions</span><textarea v-model="agentForm.systemPrompt" rows="3" placeholder="Optional instructions for this agent profile"></textarea></label>
          <div class="tool-form-grid">
            <label><span>Max turns</span><input v-model="agentForm.maxTurns" type="number" min="1" max="64" inputmode="numeric" /></label>
            <label><span>Max tool calls</span><input v-model="agentForm.maxToolCalls" type="number" min="0" max="10000" inputmode="numeric" /></label>
          </div>
          <div class="tool-form-grid">
            <label><span>Timeout seconds</span><input v-model="agentForm.timeoutSeconds" type="number" min="0" max="86400" inputmode="numeric" /></label>
            <label><span>Max delegation depth</span><input v-model="agentForm.maxDepth" type="number" min="0" max="32" inputmode="numeric" /></label>
          </div>
          <p v-if="agentFormError" class="tool-form-error">{{ agentFormError }}</p>
          <div class="tool-form-footer">
            <button class="secondary-button" type="button" @click="resetAgentForm">Reset</button>
            <button class="send-button" type="submit" :disabled="agentSaving"><span>{{ agentSaving ? 'Saving' : 'Save agent' }}</span><span class="send-arrow">↗</span></button>
          </div>
        </form>
      </div>

      <div v-if="capabilityTab === 'sub-agents'" class="tool-manager-list">
        <div v-for="profile in subAgents" :key="profile.id" class="managed-tool model-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title"><strong>{{ profile.name }}</strong><span class="tool-source">{{ String(profile.mode).toLowerCase() }}</span></div>
            <p>{{ profile.maxTurns }} turns · {{ profile.maxToolCalls }} tool calls · {{ profile.timeoutSeconds }}s · depth {{ profile.maxDepth }} · priority {{ profile.priority }} · cost {{ profile.costWeight }} · load {{ profile.maxConcurrentRuns }}</p>
          </div>
          <div class="managed-tool-actions model-actions">
            <button class="secondary-button compact" type="button" :disabled="!profile.enabled" @click="prepareSubAgentRun(profile)">Run</button>
            <button class="secondary-button compact" type="button" @click="editSubAgent(profile)">Edit</button>
            <label class="tool-toggle" :title="profile.enabled ? 'Disable sub-agent profile' : 'Enable sub-agent profile'"><input type="checkbox" :checked="profile.enabled" @change="toggleSubAgent(profile)" /><span></span></label>
            <button class="delete-tool-button" type="button" title="Delete sub-agent profile" aria-label="Delete sub-agent profile" @click="deleteSubAgent(profile)">×</button>
          </div>
        </div>
        <p v-if="subAgents.length === 0" class="tool-manager-empty">No sub-agent profiles configured.</p>

        <form class="tool-create-form inline-form" @submit.prevent="inspectSubAgentCandidates">
          <div class="tool-form-heading"><div><div class="eyebrow">ADAPTIVE ROUTING</div><h3>Inspect worker candidates</h3></div><span class="tool-form-note">Explainable scoring</span></div>
          <label><span>Task</span><input v-model="subAgentCandidateTask" placeholder="Inspect database migration logs" autocomplete="off" /></label>
          <p v-if="subAgentCandidateError" class="tool-form-error">{{ subAgentCandidateError }}</p>
          <div class="tool-form-footer"><button class="send-button" type="submit"><span>Rank candidates</span><span class="send-arrow">↗</span></button></div>
          <div v-if="subAgentCandidates.length" class="candidate-list">
            <div v-for="candidate in subAgentCandidates" :key="candidate.id" class="candidate-row">
              <div><strong>{{ candidate.name }}</strong><small>{{ candidate.reasons.join(' · ') }}<span v-if="candidate.inputPricePerMillionTokens != null || candidate.outputPricePerMillionTokens != null"> · model ${{ candidate.inputPricePerMillionTokens ?? '?' }} / ${{ candidate.outputPricePerMillionTokens ?? '?' }} per 1M</span></small></div>
              <span :class="['tool-source', candidate.available ? 'connected' : 'unhealthy']">{{ candidate.score }} · {{ candidate.activeRuns }}/{{ candidate.maxConcurrentRuns }}</span>
            </div>
          </div>
        </form>

        <div class="tool-create-form inline-form sub-agent-session-panel">
          <div class="tool-form-heading"><div><div class="eyebrow">CONTINUABLE SESSION</div><h3>Keep a sub-agent conversation alive</h3></div><span class="tool-form-note">Persistent context</span></div>
          <div class="tool-form-grid">
            <label><span>Profile</span><select v-model="subAgentRunProfileId"><option value="">Select profile</option><option v-for="profile in subAgents.filter((item) => item.enabled)" :key="profile.id" :value="profile.id">{{ profile.name }}</option></select></label>
            <label><span>Session</span><select v-model="subAgentSessionId"><option value="">Create a new session</option><option v-for="session in subAgentSessions" :key="session.id" :value="session.id">{{ session.id.slice(0, 8) }} · {{ String(session.status).toLowerCase() }}</option></select></label>
          </div>
          <div class="tool-form-footer sub-agent-session-actions"><button class="secondary-button compact" type="button" :disabled="subAgentSessionSaving" @click="createSubAgentSession">New session</button><button class="secondary-button compact" type="button" :disabled="!subAgentSessionId || subAgentSessionSaving" @click="closeSubAgentSession">Close session</button></div>
          <label><span>Message</span><textarea v-model="subAgentSessionPrompt" rows="2" placeholder="Continue the same worker conversation"></textarea></label>
          <p v-if="subAgentSessionError" class="tool-form-error">{{ subAgentSessionError }}</p>
          <div class="tool-form-footer"><button class="send-button" type="button" :disabled="subAgentSessionSaving" @click="sendSubAgentSessionMessage"><span>{{ subAgentSessionSaving ? 'Sending' : 'Send to session' }}</span><span class="send-arrow">↗</span></button></div>
        </div>

        <form class="tool-create-form inline-form" @submit.prevent="startSubAgentRun">
          <div class="tool-form-heading"><div><div class="eyebrow">BACKGROUND RUN</div><h3>Run sub-agent</h3></div><span class="tool-form-note">Live lifecycle</span></div>
          <div class="tool-form-grid">
            <label><span>Profile</span><select v-model="subAgentRunProfileId"><option value="">Select profile</option><option v-for="profile in subAgents.filter((item) => item.enabled)" :key="profile.id" :value="profile.id">{{ profile.name }}</option></select></label>
            <label><span>Task</span><input v-model="subAgentRunPrompt" placeholder="Inspect the repository and report findings" autocomplete="off" /></label>
          </div>
          <p v-if="subAgentRunError" class="tool-form-error">{{ subAgentRunError }}</p>
          <div class="tool-form-footer"><button class="send-button" type="submit" :disabled="subAgentRunStarting"><span>{{ subAgentRunStarting ? 'Starting' : 'Start run' }}</span><span class="send-arrow">↗</span></button></div>
        </form>

        <div v-if="subAgentRun" class="sub-agent-run-inspector">
          <div class="tool-form-heading"><div><div class="eyebrow">RUN INSPECTOR</div><h3>{{ subAgents.find((profile) => profile.id === subAgentRun.agentId)?.name || 'Sub-agent run' }}</h3></div><span :class="['tool-source', String(subAgentRun.status).toLowerCase()]">{{ String(subAgentRun.status).toLowerCase() }}</span></div>
          <div class="sub-agent-run-meta">{{ subAgentRun.id }} · {{ subAgentRun.modelId || 'active model' }}</div>
          <div class="tool-form-footer sub-agent-run-actions"><button v-if="String(subAgentRun.status).toUpperCase() === 'WAITING_APPROVAL'" class="secondary-button compact" type="button" @click="approveSubAgentRun(true)">Approve</button><button v-if="String(subAgentRun.status).toUpperCase() === 'WAITING_APPROVAL'" class="secondary-button compact" type="button" @click="approveSubAgentRun(false)">Deny</button><button v-if="['RUNNING', 'WAITING_APPROVAL'].includes(String(subAgentRun.status).toUpperCase())" class="secondary-button compact" type="button" @click="cancelSubAgentRun">Cancel</button><button class="secondary-button compact" type="button" @click="refreshSubAgentRun">Refresh</button></div>
          <pre v-if="subAgentRun.output" class="sub-agent-run-output">{{ subAgentRun.output }}</pre>
          <pre v-if="subAgentRun.error" class="result sub-agent-run-output">{{ subAgentRun.error }}</pre>
          <div v-if="subAgentRunEvents.length" class="plan-event-log"><div class="trace-block-label">LIVE EVENTS</div><div v-for="event in subAgentRunEvents" :key="`${event.id}-${event.type}`" class="plan-event"><span>{{ event.type }}</span><small>{{ event.payload || '' }}</small></div></div>
        </div>

        <form class="tool-create-form inline-form" @submit.prevent="saveSubAgent">
          <div class="tool-form-heading"><div><div class="eyebrow">SUB-AGENT PROFILE</div><h3>{{ subAgentForm.id ? 'Edit sub-agent' : 'Add sub-agent' }}</h3></div><span class="tool-form-note">Capability-scoped</span></div>
          <div class="tool-form-grid">
            <label><span>Name</span><input v-model="subAgentForm.name" placeholder="Research worker" autocomplete="off" /></label>
            <label><span>Mode</span><select v-model="subAgentForm.mode"><option value="chat">Chat</option><option value="planning">Planning</option><option value="execution">Execution</option></select></label>
          </div>
          <label><span>Model profile ID</span><input v-model="subAgentForm.modelId" placeholder="Optional" autocomplete="off" /></label>
          <label><span>Instructions</span><textarea v-model="subAgentForm.systemPrompt" rows="3" placeholder="Instructions for this worker"></textarea></label>
          <div class="tool-form-grid"><label><span>Allowed tools</span><input v-model="subAgentForm.allowedToolNames" placeholder="time_now, search" autocomplete="off" /></label><label><span>Allowed skills</span><input v-model="subAgentForm.skillIds" placeholder="skill_id" autocomplete="off" /></label></div>
          <div class="tool-form-grid"><label><span>Max turns</span><input v-model="subAgentForm.maxTurns" type="number" min="1" max="64" inputmode="numeric" /></label><label><span>Max tool calls</span><input v-model="subAgentForm.maxToolCalls" type="number" min="0" max="10000" inputmode="numeric" /></label></div>
          <div class="tool-form-grid"><label><span>Timeout seconds</span><input v-model="subAgentForm.timeoutSeconds" type="number" min="0" max="86400" inputmode="numeric" /></label><label><span>Max delegation depth</span><input v-model="subAgentForm.maxDepth" type="number" min="0" max="32" inputmode="numeric" /></label></div>
          <div class="tool-form-grid"><label><span>Priority</span><input v-model="subAgentForm.priority" type="number" min="0" max="100" inputmode="numeric" /></label><label><span>Cost weight</span><input v-model="subAgentForm.costWeight" type="number" min="0" max="100" step="0.1" inputmode="decimal" /></label></div>
          <div class="tool-form-grid"><label><span>Max concurrent runs</span><input v-model="subAgentForm.maxConcurrentRuns" type="number" min="1" max="64" inputmode="numeric" /></label><label><span>Capability tags</span><input v-model="subAgentForm.capabilityTags" placeholder="database, research" autocomplete="off" /></label></div>
          <p v-if="subAgentFormError" class="tool-form-error">{{ subAgentFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetSubAgentForm">Reset</button><button class="send-button" type="submit" :disabled="subAgentSaving"><span>{{ subAgentSaving ? 'Saving' : 'Save sub-agent' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>

      <div v-if="capabilityTab === 'memory'" class="tool-manager-list">
        <div class="tool-form-heading"><div><div class="eyebrow">CONTEXT MEMORY</div><h3>Conversation memories</h3></div><span class="tool-form-note">{{ memorySubjectKey() }}</span></div>
        <div v-for="memory in memories" :key="memory.id" class="managed-tool memory-item">
          <div class="managed-tool-copy"><div class="managed-tool-title"><strong>{{ memory.memoryType }}</strong><span class="tool-source">{{ memory.importance.toFixed(2) }}</span></div><p>{{ memory.content }}</p></div>
          <div class="managed-tool-actions">
            <button class="secondary-button compact" type="button" @click="editMemory(memory)">Edit</button>
            <button class="delete-tool-button" type="button" title="Delete memory" aria-label="Delete memory" @click="deleteMemory(memory)">×</button>
          </div>
        </div>
        <p v-if="memories.length === 0" class="tool-manager-empty">No memories for this conversation.</p>
        <form class="tool-create-form inline-form" @submit.prevent="saveMemory">
          <div class="tool-form-heading"><div><div class="eyebrow">EXPLICIT MEMORY</div><h3>{{ memoryEditingId === null ? 'Remember something' : 'Edit memory' }}</h3></div><span class="tool-form-note">Injected on matching runs</span></div>
          <div class="tool-form-grid"><label><span>Type</span><input v-model="memoryForm.memoryType" placeholder="fact" autocomplete="off" /></label><label><span>Importance</span><input v-model="memoryForm.importance" type="number" min="0" max="1" step="0.1" /></label></div>
          <label><span>Content</span><textarea v-model="memoryForm.content" rows="3" placeholder="The user prefers concise answers"></textarea></label>
          <p v-if="memoryFormError" class="tool-form-error">{{ memoryFormError }}</p>
          <div class="tool-form-footer"><button v-if="memoryEditingId !== null" class="secondary-button" type="button" @click="resetMemoryForm">Reset</button><button class="send-button" type="submit" :disabled="memorySaving"><span>{{ memorySaving ? 'Saving' : memoryEditingId === null ? 'Save memory' : 'Update memory' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>

      <div v-if="capabilityTab === 'context'" class="tool-manager-list">
        <div class="tool-form-heading"><div><div class="eyebrow">CONTEXT WINDOW</div><h3>Conversation context</h3></div><span class="tool-form-note">{{ conversationId || 'No conversation' }}</span></div>
        <div v-if="contextInfo" class="context-summary-grid">
          <div><span>Messages</span><strong>{{ contextInfo.messageCount }}</strong></div>
          <div><span>Estimated tokens</span><strong>{{ contextInfo.estimatedTokens }} / {{ contextInfo.maxTokens }}</strong></div>
          <div><span>State</span><strong>{{ contextInfo.truncated ? 'Compacted / truncated' : 'Within budget' }}</strong></div>
          <div><span>Providers</span><strong>{{ contextProviders.length || 'None' }}</strong></div>
        </div>
        <p v-else class="tool-manager-empty">Send a message to create a conversation context.</p>
        <p v-if="contextProviders.length" class="context-provider-list">{{ contextProviders.join(' · ') }}</p>
        <p v-if="contextError" class="tool-form-error">{{ contextError }}</p>
        <div class="tool-form-footer skill-footer"><button class="secondary-button" type="button" @click="refreshContext(); refreshContextProviders()">Refresh</button><button class="send-button" type="button" :disabled="!conversationId || contextLoading" @click="compactContext"><span>{{ contextLoading ? 'Compacting' : 'Compact context' }}</span><span class="send-arrow">↗</span></button></div>
      </div>

      <div v-if="capabilityTab === 'plans'" class="tool-manager-list">
        <div v-for="plan in plans" :key="plan.id" class="managed-tool plan-item" :class="{ selected: selectedPlanId === plan.id }">
          <button class="plan-select" type="button" @click="selectedPlanId = plan.id; refreshPlanDetail(plan.id)">
            <span class="managed-tool-copy">
              <span class="managed-tool-title"><strong>{{ plan.title }}</strong><span class="tool-source">{{ String(plan.status).toLowerCase() }}</span></span>
              <span class="managed-tool-copy"><span class="plan-goal">{{ plan.goal }}</span><span class="plan-meta">{{ plan.steps.length }} steps · {{ plan.approvalRequired ? 'approval required' : 'auto-run' }}</span></span>
            </span>
          </button>
          <div class="managed-tool-actions model-actions">
            <button v-if="plan.status === 'DRAFT'" class="secondary-button compact" type="button" @click="planAction(plan, 'approve')">Approve</button>
            <button v-if="plan.status === 'APPROVED'" class="secondary-button compact" type="button" @click="planAction(plan, 'execute')">Execute</button>
            <button v-if="plan.status === 'RUNNING'" class="secondary-button compact" type="button" @click="planAction(plan, 'cancel')">Cancel</button>
          </div>
        </div>
        <p v-if="plans.length === 0" class="tool-manager-empty">No plans configured.</p>

        <div v-if="planDetail" class="plan-detail">
          <div class="tool-form-heading">
            <div><div class="eyebrow">PLAN INSPECTOR</div><h3>{{ planDetail.title }}</h3></div>
            <span class="tool-form-note">{{ String(planDetail.status).toLowerCase() }}</span>
          </div>
          <p class="plan-detail-goal">{{ planDetail.goal }}</p>
          <div v-if="planApprovalRun" class="plan-approval-panel">
            <strong>Tool approval required</strong>
            <span>Run {{ planApprovalRun.id }}</span>
            <div class="tool-approval-actions"><button class="secondary-button compact" type="button" @click="approvePlanRun(true)">Approve</button><button class="secondary-button compact" type="button" @click="approvePlanRun(false)">Deny</button></div>
          </div>
          <div v-for="step in planDetail.steps" :key="step.id" class="plan-step" :class="String(step.status).toLowerCase()">
            <div class="plan-step-index">{{ step.stepNo }}</div>
            <div class="plan-step-copy"><strong>{{ step.title }}</strong><span>{{ String(step.status).toLowerCase() }} · {{ step.attempts }}/{{ step.maxAttempts }}</span><p>{{ step.instruction }}</p><pre v-if="step.result">{{ step.result }}</pre></div>
          </div>
          <div v-if="planEvents.length" class="plan-event-log">
            <div class="trace-block-label">LIVE EVENTS</div>
            <div v-for="event in planEvents" :key="`${event.id}-${event.type}`" class="plan-event">
              <span>{{ event.type }}</span><small>{{ event.payload || '' }}</small>
            </div>
          </div>
        </div>

        <form class="tool-create-form inline-form" @submit.prevent="createPlan">
          <div class="tool-form-heading">
            <div><div class="eyebrow">PLAN BUILDER</div><h3>Create plan</h3></div>
            <span class="tool-form-note">Approval-aware</span>
          </div>
          <label><span>Title</span><input v-model="planForm.title" placeholder="Release checklist" autocomplete="off" /></label>
          <label><span>Goal</span><textarea v-model="planForm.goal" rows="2" placeholder="What should this plan accomplish?"></textarea></label>
          <div class="tool-form-grid">
            <label><span>Agent profile ID</span><input v-model="planForm.agentId" placeholder="Optional" autocomplete="off" /></label>
            <label><span>Model profile ID</span><input v-model="planForm.modelId" placeholder="Optional" autocomplete="off" /></label>
          </div>
          <div class="tool-form-grid"><label><span>Max concurrency</span><input v-model="planForm.maxConcurrency" type="number" min="1" max="16" inputmode="numeric" /></label><label class="plan-approval-toggle"><input v-model="planForm.approvalRequired" type="checkbox" /> Require approval before execution</label></div>
          <div v-for="(step, index) in planForm.steps" :key="index" class="plan-form-step">
            <div class="plan-form-step-header"><span>STEP {{ index + 1 }}</span><button v-if="planForm.steps.length > 1" class="delete-tool-button" type="button" title="Remove step" aria-label="Remove step" @click="removePlanStep(index)">×</button></div>
            <label><span>Title</span><input v-model="step.title" placeholder="Build" autocomplete="off" /></label>
            <label><span>Instruction</span><textarea v-model="step.instruction" rows="2" placeholder="Tell the execution agent what to do"></textarea></label>
            <label><span>Sub-agent profile ID</span><input v-model="step.subAgentId" placeholder="Optional" autocomplete="off" /></label>
            <label><span>Depends on step numbers</span><input v-model="step.dependsOn" placeholder="1, 2" autocomplete="off" /></label>
            <label><span>Max attempts</span><input v-model="step.maxAttempts" type="number" min="1" max="10" inputmode="numeric" /></label>
          </div>
          <button class="secondary-button" type="button" @click="addPlanStep">+ Add step</button>
          <p v-if="planFormError" class="tool-form-error">{{ planFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetPlanForm">Reset</button><button class="send-button" type="submit" :disabled="planSaving"><span>{{ planSaving ? 'Creating' : 'Create plan' }}</span><span class="send-arrow">↗</span></button></div>
        </form>

        <form class="tool-create-form inline-form" @submit.prevent="createAdaptivePlan">
          <div class="tool-form-heading"><div><div class="eyebrow">ADAPTIVE PLANNER</div><h3>Generate from task</h3></div><span class="tool-form-note">Planner + auto delegation</span></div>
          <label><span>Task</span><textarea v-model="adaptivePlanForm.prompt" rows="3" placeholder="Describe a complex task and let the planner split it into executable steps"></textarea></label>
          <div class="tool-form-grid"><label><span>Max steps</span><input v-model="adaptivePlanForm.maxSteps" type="number" min="1" max="16" inputmode="numeric" /></label><label><span>Max concurrency</span><input v-model="adaptivePlanForm.maxConcurrency" type="number" min="1" max="16" inputmode="numeric" /></label></div>
          <label class="plan-approval-toggle"><input v-model="adaptivePlanForm.approvalRequired" type="checkbox" /> Require approval</label>
          <label class="plan-approval-toggle"><input v-model="adaptivePlanForm.allowDynamicSubAgents" type="checkbox" /> Allow adaptive worker creation</label>
          <div v-if="adaptivePlanReasoning || adaptivePlanOutput" class="adaptive-plan-stream">
            <details v-if="adaptivePlanReasoning" class="reasoning-block" open><summary>Planning reasoning</summary><div class="markdown-content" v-html="renderMarkdown(adaptivePlanReasoning)"></div></details>
            <pre v-if="adaptivePlanOutput">{{ adaptivePlanOutput }}</pre>
          </div>
          <div class="tool-form-footer"><button class="send-button" type="submit" :disabled="planSaving"><span>{{ planSaving ? 'Planning' : 'Generate adaptive plan' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>
    </section>
  </div>
</template>
