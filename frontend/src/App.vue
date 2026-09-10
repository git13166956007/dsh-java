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
const runtime = ref({ runtimeStarted: false, pluginCount: 0 })
const tools = ref([])
const toolManagerOpen = ref(false)
const capabilityTab = ref('tools')
const mcpServers = ref([])
const skills = ref([])
const models = ref([])
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
  allowedToolNames: '',
  skillIds: '',
  enabled: true
})
const subAgentFormError = ref('')
const subAgentSaving = ref(false)
const agentForm = ref({
  id: null,
  name: '',
  mode: 'chat',
  modelId: '',
  systemPrompt: '',
  maxTurns: 8,
  enabled: true,
  active: false
})
const agentFormError = ref('')
const agentSaving = ref(false)
const plans = ref([])
const selectedPlanId = ref(null)
const planDetail = ref(null)
const planForm = ref({
  title: '',
  goal: '',
  agentId: '',
  modelId: '',
  approvalRequired: true,
  steps: [{ title: '', instruction: '', maxAttempts: 1, subAgentId: '' }]
})
const planFormError = ref('')
const planSaving = ref(false)
const adaptivePlanForm = ref({ prompt: '', maxSteps: 6, approvalRequired: true })
let planPollTimer = null
const modelForm = ref({
  id: null,
  name: '',
  provider: 'deepseek',
  baseUrl: 'https://api.deepseek.com',
  model: 'deepseek-v4-flash',
  apiKey: '',
  proxyHost: '',
  proxyPort: '',
  enabled: true,
  active: false
})
const modelFormError = ref('')
const modelSaving = ref(false)
const mcpForm = ref({ name: '', transport: 'stdio', endpoint: '', command: '', arguments: '' })
const mcpFormError = ref('')
const mcpSaving = ref(false)
const toolForm = ref({
  name: '',
  description: '',
  parameters: '{\n  "type": "object",\n  "properties": {}\n}',
  result: ''
})
const toolFormError = ref('')
const toolSaving = ref(false)
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
    mcpServers.value = await response.json()
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
    const response = await fetch('/api/v1/models')
    if (!response.ok) throw new Error('模型列表不可用')
    models.value = await response.json()
    if (!selectedModelId.value || !models.value.some((model) => model.id === selectedModelId.value)) {
      selectedModelId.value = models.value.find((model) => model.active && model.enabled)?.id || models.value.find((model) => model.enabled)?.id || null
    }
  } catch {
    models.value = []
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

function resetSubAgentForm() {
  subAgentForm.value = {
    id: null,
    name: '',
    mode: 'execution',
    modelId: '',
    systemPrompt: '',
    maxTurns: 8,
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
  if (planDetail.value.status === 'RUNNING') {
    clearTimeout(planPollTimer)
    planPollTimer = setTimeout(() => refreshPlanDetail(id), 1200)
  }
}

function resetPlanForm() {
  planForm.value = {
    title: '',
    goal: '',
    agentId: selectedAgentId.value || '',
    modelId: selectedModelId.value || '',
    approvalRequired: true,
    steps: [{ title: '', instruction: '', maxAttempts: 1, subAgentId: '' }]
  }
  planFormError.value = ''
}

function addPlanStep() {
  planForm.value.steps.push({ title: '', instruction: '', maxAttempts: 1, subAgentId: '' })
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
        steps: planForm.value.steps.map((step) => ({
          title: step.title.trim(),
          instruction: step.instruction.trim(),
          maxAttempts: Number(step.maxAttempts) || 1,
          subAgentId: step.subAgentId.trim() || null
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
  try {
    const response = await fetch('/api/v1/plans/adaptive', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt: adaptivePlanForm.value.prompt.trim(),
        apiKey: apiKey.value.trim() || null,
        agentId: selectedAgentId.value,
        modelId: selectedModelId.value,
        approvalRequired: adaptivePlanForm.value.approvalRequired,
        maxSteps: Number(adaptivePlanForm.value.maxSteps) || 6
      })
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(payload.message || payload.error || 'Adaptive plan creation failed')
    selectedPlanId.value = payload.id
    planDetail.value = payload
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
  refreshAgents()
  refreshSubAgents()
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
    result: ''
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
        result: toolForm.value.result
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

async function deleteTool(tool) {
  if (!tool.removable) return
  const response = await fetch(`/api/v1/tools/${encodeURIComponent(tool.name)}`, { method: 'DELETE' })
  if (response.ok) tools.value = tools.value.filter((item) => item.name !== tool.name)
}

function resetMcpForm() {
  mcpForm.value = { name: '', transport: 'stdio', endpoint: '', command: '', arguments: '' }
  mcpFormError.value = ''
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
    const response = await fetch('/api/v1/mcp/servers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: mcpForm.value.name.trim(),
        transport: mcpForm.value.transport,
        endpoint: mcpForm.value.endpoint.trim() || null,
        command: mcpForm.value.command.trim() || null,
        arguments: mcpForm.value.arguments.split(/\s+/).filter(Boolean)
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
  await refreshTools()
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

function resetModelForm() {
  modelForm.value = {
    id: null,
    name: '',
    provider: 'deepseek',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-v4-flash',
    apiKey: '',
    proxyHost: '',
    proxyPort: '',
    enabled: true,
    active: false
  }
  modelFormError.value = ''
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
    active: model.active
  }
  modelFormError.value = ''
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
        apiKey: modelForm.value.apiKey.trim() || null,
        proxyHost: modelForm.value.proxyHost.trim() || null,
        proxyPort: Number(modelForm.value.proxyPort) || 0,
        enabled: modelForm.value.enabled,
        active: modelForm.value.active
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
  const assistantMessage = { role: 'assistant', content: '' }
  messages.value.push(assistantMessage)
  trace.value = []
  sending.value = true
  await scrollTranscript()

  try {
    const response = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
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
      } else if (event === 'tool_call') {
        const toolMessage = {
          role: 'tool',
          id: data.id,
          name: data.name,
          arguments: data.arguments,
          result: null,
          state: 'running'
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
        assistantMessage.content = data.answer || assistantMessage.content
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
    const assistantPosition = messages.value.indexOf(assistantMessage)
    if (assistantPosition >= 0) messages.value.splice(assistantPosition, 1)
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
  conversationId.value = null
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

onMounted(() => {
  refreshHealth()
  refreshTools()
  refreshMcpServers()
  refreshSkills()
  refreshModels()
  refreshAgents()
  refreshSubAgents()
  refreshPlans()
})

onUnmounted(() => clearTimeout(planPollTimer))
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
        <span class="model-name">{{ models.find((item) => item.id === selectedModelId)?.model || 'No model' }}</span>
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
          <button class="tools-button" type="button" title="Manage tools" @click="openToolManager">
            <span>Tools</span>
            <span class="tools-button-count">{{ tools.length }}</span>
          </button>
          <button class="tools-button" type="button" title="Manage MCP servers" @click="openCapabilities('mcp')">
            <span>MCP</span>
            <span class="tools-button-count">{{ mcpServers.filter((item) => item.status === 'CONNECTED').length }}</span>
          </button>
          <button class="tools-button" type="button" title="Manage skills" @click="openCapabilities('skills')">
            <span>Skills</span>
            <span class="tools-button-count">{{ skills.filter((item) => item.enabled).length }}</span>
          </button>
          <select v-model="selectedModelId" class="model-picker" title="Select model" :disabled="sending">
            <option v-for="model in models.filter((item) => item.enabled)" :key="model.id" :value="model.id">{{ model.name }} · {{ model.model }}</option>
            <option v-if="models.filter((item) => item.enabled).length === 0" :value="null">No model</option>
          </select>
          <select v-model="selectedAgentId" class="model-picker" title="Select agent profile" :disabled="sending" @change="applyAgentSelection">
            <option v-for="agent in agents.filter((item) => item.enabled)" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
            <option v-if="agents.filter((item) => item.enabled).length === 0" :value="null">No agent</option>
          </select>
          <select v-model="selectedMode" class="mode-picker" title="Select run mode" :disabled="sending">
            <option value="chat">Chat</option>
            <option value="planning">Planning</option>
            <option value="execution">Execution</option>
          </select>
          <button class="tools-button" type="button" title="Manage models" @click="openCapabilities('models')">
            <span>Models</span>
            <span class="tools-button-count">{{ models.length }}</span>
          </button>
          <button class="tools-button" type="button" title="Manage plans" @click="openCapabilities('plans')">
            <span>Plans</span>
            <span class="tools-button-count">{{ plans.length }}</span>
          </button>
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

        <article v-for="(item, index) in messages" :key="`${item.role}-${index}`" class="message-row" :class="[item.role, { pending: item.role === 'assistant' && !item.content }]">
          <div class="message-avatar">{{ item.role === 'user' ? 'S' : item.role === 'error' ? '!' : item.role === 'tool' ? 'T' : 'D' }}</div>
          <div class="message-body">
            <div class="message-meta">
              <strong>{{ item.role === 'user' ? 'You' : item.role === 'error' ? 'Runtime' : item.role === 'tool' ? 'Tool execution' : 'DSH Agent' }}</strong>
              <span>{{ item.role === 'user' ? 'prompt' : item.role === 'error' ? 'error' : item.role === 'tool' ? item.state : 'answer' }}</span>
            </div>
            <div v-if="item.role === 'assistant'" class="message-content markdown-content" v-html="renderMarkdown(item.content)"></div>
            <div v-else-if="item.role === 'tool'" class="tool-message-content">
              <div class="tool-message-title"><strong>{{ item.name }}</strong><span>{{ item.state === 'running' ? 'Running' : 'Completed' }}</span></div>
              <div class="tool-message-label">INPUT</div>
              <pre>{{ formatArguments(item.arguments) }}</pre>
              <template v-if="item.result !== null">
                <div class="tool-message-label">OUTPUT</div>
                <pre class="result">{{ item.result }}</pre>
              </template>
            </div>
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
        <button :class="{ active: capabilityTab === 'agents' }" type="button" @click="capabilityTab = 'agents'">Agents</button>
        <button :class="{ active: capabilityTab === 'sub-agents' }" type="button" @click="capabilityTab = 'sub-agents'">Sub-agents</button>
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
            <p>{{ server.transport }} · {{ server.endpoint || server.command }}</p>
          </div>
          <div class="managed-tool-actions">
            <button v-if="server.status === 'CONNECTED'" class="secondary-button compact" type="button" @click="mcpAction(server, 'disconnect')">断开</button>
            <button v-else class="secondary-button compact" type="button" @click="mcpAction(server, 'connect')">连接</button>
            <button v-if="server.status === 'CONNECTED'" class="secondary-button compact" type="button" title="Refresh MCP tools" @click="mcpAction(server, 'refresh')">刷新</button>
            <button class="delete-tool-button" type="button" title="Delete MCP server" aria-label="Delete MCP server" @click="deleteMcpServer(server)">×</button>
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
          <label v-if="mcpForm.transport !== 'stdio'"><span>Endpoint</span><input v-model="mcpForm.endpoint" :placeholder="mcpForm.transport === 'streamable_http' ? 'https://mcp.amap.com/mcp?key=YOUR_KEY' : 'http://localhost:3000/sse'" autocomplete="off" /></label>
          <label v-else><span>Command</span><input v-model="mcpForm.command" placeholder="npx" autocomplete="off" /></label>
          <label><span>{{ mcpForm.transport === 'stdio' ? 'Arguments' : 'Endpoint hint' }}</span><input v-if="mcpForm.transport === 'stdio'" v-model="mcpForm.arguments" placeholder="-y @modelcontextprotocol/server-filesystem /tmp" autocomplete="off" /><input v-else :value="mcpForm.transport === 'streamable_http' ? 'AMap: https://mcp.amap.com/mcp?key=YOUR_KEY' : 'Use the server SSE endpoint, for example /sse'" disabled /></label>
          <p v-if="mcpFormError" class="tool-form-error">{{ mcpFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetMcpForm">Reset</button><button class="send-button" type="submit" :disabled="mcpSaving"><span>{{ mcpSaving ? 'Adding' : 'Add server' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>

      <div v-if="capabilityTab === 'skills'" class="tool-manager-list">
        <div v-for="skill in skills" :key="skill.id" class="managed-tool skill-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title"><strong>{{ skill.name }}</strong><span class="tool-source">{{ skill.id }}</span></div>
            <p>{{ skill.description }}</p>
            <details><summary>查看 SKILL.md</summary><pre class="skill-content">{{ skill.content }}</pre></details>
          </div>
          <label class="tool-toggle" :title="skill.enabled ? 'Disable skill' : 'Enable skill'"><input type="checkbox" :checked="skill.enabled" @change="toggleSkill(skill)" /><span></span></label>
        </div>
        <p v-if="skills.length === 0" class="tool-manager-empty">No skills found. Add skills/&lt;name&gt;/SKILL.md and refresh.</p>
        <div class="tool-form-footer skill-footer"><button class="secondary-button" type="button" @click="refreshSkills">Refresh skills</button></div>
      </div>

      <div v-if="capabilityTab === 'models'" class="tool-manager-list">
        <div v-for="model in models" :key="model.id" class="managed-tool model-item">
          <div class="managed-tool-copy">
            <div class="managed-tool-title">
              <strong>{{ model.name }}</strong>
              <span :class="['tool-source', model.active ? 'connected' : '']">{{ model.active ? 'DEFAULT' : model.provider }}</span>
            </div>
            <p>{{ model.model }} · {{ model.baseUrl }}<br />{{ model.apiKeyConfigured ? 'API key configured' : 'Uses request or environment API key' }}</p>
          </div>
          <div class="managed-tool-actions model-actions">
            <button v-if="!model.active && model.enabled" class="secondary-button compact" type="button" @click="activateModel(model)">Default</button>
            <button class="secondary-button compact" type="button" @click="editModel(model)">Edit</button>
            <label class="tool-toggle" :title="model.enabled ? 'Disable model' : 'Enable model'">
              <input type="checkbox" :checked="model.enabled" @change="toggleModel(model)" />
              <span></span>
            </label>
            <button v-if="models.length > 1" class="delete-tool-button" type="button" title="Delete model" aria-label="Delete model" @click="deleteModel(model)">×</button>
          </div>
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
            <label><span>Provider</span><input v-model="modelForm.provider" placeholder="deepseek" autocomplete="off" /></label>
          </div>
          <label><span>Base URL</span><input v-model="modelForm.baseUrl" placeholder="https://api.deepseek.com" autocomplete="off" /></label>
          <label><span>Model</span><input v-model="modelForm.model" placeholder="deepseek-v4-flash" autocomplete="off" /></label>
          <label><span>API Key</span><input v-model="modelForm.apiKey" type="password" autocomplete="new-password" :placeholder="modelForm.id ? 'Leave blank to keep current key' : 'Optional; request key can override'" /></label>
          <div class="tool-form-grid">
            <label><span>Proxy Host</span><input v-model="modelForm.proxyHost" placeholder="127.0.0.1" autocomplete="off" /></label>
            <label><span>Proxy Port</span><input v-model="modelForm.proxyPort" inputmode="numeric" placeholder="7897" autocomplete="off" /></label>
          </div>
          <p v-if="modelFormError" class="tool-form-error">{{ modelFormError }}</p>
          <div class="tool-form-footer">
            <button class="secondary-button" type="button" @click="resetModelForm">Reset</button>
            <button class="send-button" type="submit" :disabled="modelSaving"><span>{{ modelSaving ? 'Saving' : 'Save model' }}</span><span class="send-arrow">↗</span></button>
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
            <p>{{ String(agent.mode).toLowerCase() }} · {{ agent.maxTurns }} turns{{ agent.modelId ? ` · ${agent.modelId}` : ' · active model' }}</p>
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
          <label><span>Max turns</span><input v-model="agentForm.maxTurns" type="number" min="1" max="64" inputmode="numeric" /></label>
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
            <p>{{ profile.maxTurns }} turns · {{ profile.allowedToolNames.length }} tools · {{ profile.skillIds.length }} skills</p>
          </div>
          <div class="managed-tool-actions model-actions">
            <button class="secondary-button compact" type="button" @click="editSubAgent(profile)">Edit</button>
            <label class="tool-toggle" :title="profile.enabled ? 'Disable sub-agent profile' : 'Enable sub-agent profile'"><input type="checkbox" :checked="profile.enabled" @change="toggleSubAgent(profile)" /><span></span></label>
            <button class="delete-tool-button" type="button" title="Delete sub-agent profile" aria-label="Delete sub-agent profile" @click="deleteSubAgent(profile)">×</button>
          </div>
        </div>
        <p v-if="subAgents.length === 0" class="tool-manager-empty">No sub-agent profiles configured.</p>

        <form class="tool-create-form inline-form" @submit.prevent="saveSubAgent">
          <div class="tool-form-heading"><div><div class="eyebrow">SUB-AGENT PROFILE</div><h3>{{ subAgentForm.id ? 'Edit sub-agent' : 'Add sub-agent' }}</h3></div><span class="tool-form-note">Capability-scoped</span></div>
          <div class="tool-form-grid">
            <label><span>Name</span><input v-model="subAgentForm.name" placeholder="Research worker" autocomplete="off" /></label>
            <label><span>Mode</span><select v-model="subAgentForm.mode"><option value="chat">Chat</option><option value="planning">Planning</option><option value="execution">Execution</option></select></label>
          </div>
          <label><span>Model profile ID</span><input v-model="subAgentForm.modelId" placeholder="Optional" autocomplete="off" /></label>
          <label><span>Instructions</span><textarea v-model="subAgentForm.systemPrompt" rows="3" placeholder="Instructions for this worker"></textarea></label>
          <div class="tool-form-grid"><label><span>Allowed tools</span><input v-model="subAgentForm.allowedToolNames" placeholder="time_now, search" autocomplete="off" /></label><label><span>Allowed skills</span><input v-model="subAgentForm.skillIds" placeholder="skill_id" autocomplete="off" /></label></div>
          <label><span>Max turns</span><input v-model="subAgentForm.maxTurns" type="number" min="1" max="64" inputmode="numeric" /></label>
          <p v-if="subAgentFormError" class="tool-form-error">{{ subAgentFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetSubAgentForm">Reset</button><button class="send-button" type="submit" :disabled="subAgentSaving"><span>{{ subAgentSaving ? 'Saving' : 'Save sub-agent' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
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
          <div v-for="step in planDetail.steps" :key="step.id" class="plan-step" :class="String(step.status).toLowerCase()">
            <div class="plan-step-index">{{ step.stepNo }}</div>
            <div class="plan-step-copy"><strong>{{ step.title }}</strong><span>{{ String(step.status).toLowerCase() }} · {{ step.attempts }}/{{ step.maxAttempts }}</span><p>{{ step.instruction }}</p><pre v-if="step.result">{{ step.result }}</pre></div>
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
          <label class="plan-approval-toggle"><input v-model="planForm.approvalRequired" type="checkbox" /> Require approval before execution</label>
          <div v-for="(step, index) in planForm.steps" :key="index" class="plan-form-step">
            <div class="plan-form-step-header"><span>STEP {{ index + 1 }}</span><button v-if="planForm.steps.length > 1" class="delete-tool-button" type="button" title="Remove step" aria-label="Remove step" @click="removePlanStep(index)">×</button></div>
            <label><span>Title</span><input v-model="step.title" placeholder="Build" autocomplete="off" /></label>
            <label><span>Instruction</span><textarea v-model="step.instruction" rows="2" placeholder="Tell the execution agent what to do"></textarea></label>
            <label><span>Sub-agent profile ID</span><input v-model="step.subAgentId" placeholder="Optional" autocomplete="off" /></label>
            <label><span>Max attempts</span><input v-model="step.maxAttempts" type="number" min="1" max="10" inputmode="numeric" /></label>
          </div>
          <button class="secondary-button" type="button" @click="addPlanStep">+ Add step</button>
          <p v-if="planFormError" class="tool-form-error">{{ planFormError }}</p>
          <div class="tool-form-footer"><button class="secondary-button" type="button" @click="resetPlanForm">Reset</button><button class="send-button" type="submit" :disabled="planSaving"><span>{{ planSaving ? 'Creating' : 'Create plan' }}</span><span class="send-arrow">↗</span></button></div>
        </form>

        <form class="tool-create-form inline-form" @submit.prevent="createAdaptivePlan">
          <div class="tool-form-heading"><div><div class="eyebrow">ADAPTIVE PLANNER</div><h3>Generate from task</h3></div><span class="tool-form-note">Planner + auto delegation</span></div>
          <label><span>Task</span><textarea v-model="adaptivePlanForm.prompt" rows="3" placeholder="Describe a complex task and let the planner split it into executable steps"></textarea></label>
          <div class="tool-form-grid"><label><span>Max steps</span><input v-model="adaptivePlanForm.maxSteps" type="number" min="1" max="16" inputmode="numeric" /></label><label class="plan-approval-toggle"><input v-model="adaptivePlanForm.approvalRequired" type="checkbox" /> Require approval</label></div>
          <div class="tool-form-footer"><button class="send-button" type="submit" :disabled="planSaving"><span>{{ planSaving ? 'Planning' : 'Generate adaptive plan' }}</span><span class="send-arrow">↗</span></button></div>
        </form>
      </div>
    </section>
  </div>
</template>
