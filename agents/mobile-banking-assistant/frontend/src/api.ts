/** 后端契约的前端镜像。字段名与 Java 侧一一对应，改名要两边一起改。 */

export type Decision =
  | 'DIRECT_KNOWLEDGE' | 'NAVIGATION' | 'EXECUTE_CAPABILITY' | 'START_WORKFLOW'
  | 'STATIC_PLAN' | 'DELEGATE_GOAL' | 'START_LOOP' | 'RESUME_TASK' | 'RESUME_LOOP'
  | 'CLARIFY' | 'CANCEL' | 'REJECT' | 'HANDOFF'

export interface ResponseAction {
  event: string
  label: string
  ref: string
  version: number
  style: 'PRIMARY' | 'SECONDARY' | 'DANGER'
}

export interface RouteDecision {
  decision: Decision
  /** 复数。多意图时会有多个，页面不能假设只有一个 */
  candidateIds: string[]
  target?: { type: string; id: string } | null
  taskShape?: string | null
  confidence: number | null
  reasonCode: string | null
  missingSlots: string[]
  evidenceRefs: string[]
  modelVersion: string
  promptVersion: string
  configVersion: string
  /** 未短路时是 NONE 而不是 null */
  shortCircuit: string
}

export interface ChatResponse {
  traceId: string
  text: string
  decision: RouteDecision
  plan: ResponsePlanView
  taskId: string | null
  usedTemplate: string | null
  fellBack: boolean
  degradedChannels: string[]
  actions: ResponseAction[]
}

export interface ChatStreamEvent {
  sequence: number
  type: 'TURN_STARTED' | 'CARD_AVAILABLE' | 'TURN_COMPLETED' | 'TURN_FAILED'
  component?: ResponseComponent | null
  itemIndex?: number | null
  itemCount?: number | null
  response?: ChatResponse | null
  message?: string | null
}

export type ResponseComponent =
  | 'CHOICE_LIST' | 'TASK_PROGRESS' | 'REVIEW_SUMMARY' | 'RESULT_SUMMARY'
  | 'NAVIGATION' | 'LOOP_STATUS' | 'RISK_NOTICE' | 'PRODUCT_COMPARISON' | 'MENU_LIST' | string

export interface ResponsePlanView extends Record<string, unknown> {
  taskId?: string | null
  sceneCode?: string | null
  responsePhase?: 'ACK' | 'PROGRESS' | 'CLARIFY' | 'REVIEW' | 'SWITCH_REVIEW' | 'CONFIRM' | 'FINAL' | 'ERROR' | string
  templateKey?: string | null
  slots?: Record<string, unknown>
  cardComponents?: ResponseComponent[]
  actionCodes?: string[]
  riskNoticeCodes?: string[]
}

export interface Overview {
  assetVersion: string
  capabilityCount: number
  strongRuleCount: number
  negativeRuleCount: number
  writeEnabled: boolean
  index: {
    state: string
    indexName: string | null
    assetVersion: string | null
    vectorsIndexed: boolean
    documentCount: number
    searchable: boolean
    semanticAvailable: boolean
    stale: boolean
  }
  lint: { errors: Finding[]; warnings: Finding[] }
}

export interface ConsoleSettings {
  agent: {
    id: string
    displayName: string
  }
  tenantDefaults: Tenant & {
    channels: string[]
  }
  chat: {
    sessionPrefix: string
    defaultPage: string
    pages: string[]
    defaultUserState: string
    userStates: string[]
    exampleQueries: string[]
  }
  operations: {
    refreshIntervalMillis: number
    autoRefreshEnabled: boolean
  }
}

export interface Finding {
  severity: 'ERROR' | 'WARN'
  rule: string
  where: string
  detail: string
}

export interface PathCandidate {
  candidateId: string
  fusedScore: number
  semantic: number
  rule: number
  negative: number
}

/** 改写阶段文本形态，对齐快路径 RewriteResult */
export interface PipelineDetail {
  originalQuery?: string | null
  normalizedQuery?: string | null
  searchText?: string | null
  semanticText?: string | null
  terms?: string[]
  slots?: Record<string, unknown>
}

export interface PathSummary {
  exitPath: string
  arbitratedBy: string
  phaseMs: Record<string, number>
  topCandidates: PathCandidate[]
  selectedRank: number | null
  overruledTop1: boolean | null
  runnerUpId: string | null
  missingSlots: string[]
  eventType: string | null
  /** 对应 OTEL huawei.finance.agent.decision.model_version；未调模型为 null */
  modelVersion?: string | null
  /** 对应 OTEL huawei.finance.agent.decision.prompt_version */
  promptVersion?: string | null
  /** 头两名融合分差，对应 huawei.finance.agent.recall.margin */
  margin?: number | null
  /** 改写 / 槽位等阶段输入输出 */
  pipeline?: PipelineDetail | null
}

export interface RuntimeModuleStep {
  module: string
  operation: string
  role: 'MAIN' | 'CHILD' | 'CONTEXT' | 'MEMORY' | 'CACHE' | string
  input: Record<string, unknown>
  output: Record<string, unknown>
  outcome: string
  durationMs: number | null
}

export interface CollaborationHop {
  delegationId: string
  sourceAgentId: string
  targetAgentId: string
  rootTaskId: string | null
  parentTaskId: string | null
  sourceTaskId: string | null
  mode: 'GOAL' | 'TASK'
  capabilityId: string | null
  depth: number
  outcome: string | null
  reasonCode: string | null
}

export interface CollaborationTask {
  taskId: string
  agentId: string
  capabilityId: string | null
  state: string
  intentPath: string | null
  invocationOrigin: string | null
  guardrailStatus: string | null
  failureClass: string | null
  createdAt?: string | null
}

export interface PlanItem {
  order?: number
  text?: string
  capabilityId?: string | null
  summary?: string
  relation?: string
  condition?: string | null
  resolutionStrength?: 'LOCKED' | 'PREFERRED' | 'UNRESOLVED'
  topScore?: number
  margin?: number
  candidateIds?: string[]
  evidenceRefs?: string[]
  resolution?: {
    strength?: 'LOCKED' | 'PREFERRED' | 'UNRESOLVED'
    topScore?: number
    margin?: number
    candidateIds?: string[]
    evidenceRefs?: string[]
  }
}

export interface PlanExecution {
  planId?: string
  cursor?: number
  stepCount?: number
  state?: string
  original?: string
  source?: string
  /** 计划蓝图：拆出来的子意图（含条件） */
  items?: PlanItem[]
  steps?: Array<{
    stepIndex: number
    capabilityId: string
    taskId: string | null
    status: string
    failureClass: string
    reasonCode: string | null
    factKeys: unknown
  }>
}

export interface LoopExecution {
  loopId: string
  status: string
  reasonCode?: string | null
  iteration: number
  maxIterations: number
  stateVersion: number
  candidateIds: string[]
  updatedAt?: string | null
  steps: Array<{
    stepIndex: number
    actionType?: string | null
    targetId?: string | null
    proposalReasonCode?: string | null
    status: string
    taskId?: string | null
    reasonCode?: string | null
    createdAt?: string | null
    completedAt?: string | null
    observation?: {
      status: string
      sourceType?: string | null
      sourceId?: string | null
      reasonCode?: string | null
      failureClass?: string | null
      retryable: boolean
    } | null
  }>
}

export interface RecentEntry {
  /** 新服务返回 ISO-8601；滚动升级期间兼容旧服务的 epoch 秒。 */
  at: string | number
  traceId: string
  sessionId: string
  query: string
  decision: Decision
  reasonCode: string | null
  shortCircuit: string | null
  capabilityId: string | null
  confidence: number | null
  taskId: string | null
  templateKey: string | null
  fellBack: boolean
  degradedChannels: string[]
  elapsedMillis: number
  path?: PathSummary
  gatewayCalls?: string[]
  moduleSteps?: RuntimeModuleStep[]
  collaboration?: {
    delegations?: CollaborationHop[]
    tasks?: CollaborationTask[]
  }
  planExecution?: PlanExecution
  loopExecution?: LoopExecution
}

export interface FileEntry {
  path: string
  category: string
  size: number
}

export type RenderMode = 'TEMPLATE' | 'MODEL_SELECT' | 'POLISH' | 'GENERATE'

export interface ResponsePolicyRule {
  tenant: string
  agent: string
  scene: string
  phase: string
  mode: RenderMode
  model: string
  templateSet: string[]
  temperature: number
  maxTokens: number
}

export interface ResponsePolicy {
  version: string
  promptVersion: string
  systemPrompt: string
  defaults: ResponsePolicyRule
  rules: ResponsePolicyRule[]
}

export interface AgentDirectoryEntry {
  agentId: string
  displayName: string
  roles: string[]
  domains: string[]
  implementationMode: 'application' | 'extension' | 'scaffold'
  implementationStatus: 'IMPLEMENTED' | 'SCAFFOLD'
  runtimeStatus: 'ONLINE' | 'UNHEALTHY' | 'OFFLINE'
  capabilities: string[]
  instances: number
}

export interface AgentDirectory {
  agents: AgentDirectoryEntry[]
  summary: {
    configured: number
    online: number
    unhealthy: number
    offline: number
    scaffold: number
    implemented: number
  }
  configured: unknown[]
  inProcess: unknown[]
  registry: { enabled: boolean; instances?: unknown[] }
}

/**
 * 租户头。
 *
 * 生产上这三个头由渠道网关在鉴权之后注入，页面自己填只在联调环境成立。
 * 之所以让它们可见可改，是因为 spaceId 参与出口缓存键——不给操作者看见，
 * 「换个租户结论就变了」这件事就无从解释。
 */
export interface Tenant {
  userId: string
  spaceId: string
  channel: string
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  const text = await response.text()
  const body = text ? JSON.parse(text) : null
  if (!response.ok) {
    throw Object.assign(new Error(body?.message ?? `HTTP ${response.status}`), { body })
  }
  return body as T
}

export const api = {
  chat(query: string, sessionId: string, tenant: Tenant, page: string, userState: string,
       action?: { event: string; ref: string; version: number }) {
    return request<ChatResponse>('/api/v1/chat', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-User-ID': tenant.userId,
        'X-Space-ID': tenant.spaceId,
        'X-Channel-ID': tenant.channel,
      },
      body: JSON.stringify({
        sessionId,
        userId: tenant.userId,
        query,
        channel: tenant.channel,
        page,
        userState,
        action,
      }),
    })
  },

  async chatStream(query: string, sessionId: string, tenant: Tenant, page: string, userState: string,
                   action: { event: string; ref: string; version: number } | undefined,
                   onEvent: (event: ChatStreamEvent) => void) {
    const response = await fetch('/api/v1/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'X-User-ID': tenant.userId,
        'X-Space-ID': tenant.spaceId,
        'X-Channel-ID': tenant.channel,
      },
      body: JSON.stringify({ sessionId, userId: tenant.userId, query, channel: tenant.channel,
        page, userState, action }),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    if (!response.body) throw new Error('流式响应不可用')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n')
      const frames = buffer.split('\n\n')
      buffer = frames.pop() ?? ''
      for (const frame of frames) {
        const data = frame.split('\n').filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart()).join('\n')
        if (data) onEvent(JSON.parse(data) as ChatStreamEvent)
      }
      if (done) break
    }
    if (buffer.trim()) {
      const data = buffer.split('\n').filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trimStart()).join('\n')
      if (data) onEvent(JSON.parse(data) as ChatStreamEvent)
    }
  },

  settings: () => request<ConsoleSettings>('/internal/console/settings'),
  overview: () => request<Overview>('/internal/console/overview'),
  capabilities: () => request<any[]>('/internal/console/capabilities'),
  rules: () => request<{
    strongRules: unknown[]
    negativeRules: unknown[]
    fusion: { channels?: Record<string, boolean> } & Record<string, unknown>
    clarify: unknown
  }>('/internal/console/rules'),
  metrics: () => request<Record<string, any>>('/internal/console/metrics'),
  recent: () => request<RecentEntry[]>('/internal/console/recent'),
  agents: () => request<AgentDirectory>('/internal/agents'),

  files: () => request<FileEntry[]>('/internal/console/assets/files'),
  file: (path: string) =>
    request<{ path: string; content: string }>(
      `/internal/console/assets/file?path=${encodeURIComponent(path)}`,
    ),
  save: (path: string, content: string) =>
    request<{
      assetVersion: string
      findings: Finding[]
      indexStale: boolean
      indexState?: string
      indexDocumentCount?: number
      vectorsIndexed?: boolean
    }>('/internal/console/assets/file', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path, content }),
    }),
  reindex: () =>
    request<{ assetVersion: string; vectorsIndexed: boolean; documentCount: number }>(
      '/internal/console/assets/reindex',
      { method: 'POST' },
    ),

  cache: (kind?: string) =>
    request<CacheBrowser>(
      kind
        ? `/internal/console/cache?kind=${encodeURIComponent(kind)}`
        : '/internal/console/cache',
    ),
  decisionCacheControl: () =>
    request<DecisionCacheControl>('/internal/console/decision-cache-control'),
  setDecisionCacheEnabled: (enabled: boolean) =>
    request<DecisionCacheControl & { cleared: number }>(
      '/internal/console/decision-cache-control',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled }),
      },
    ),
  deleteCache: (key: string) =>
    request<{ deleted: boolean; key: string }>(
      `/internal/console/cache?key=${encodeURIComponent(key)}`,
      { method: 'DELETE' },
    ),
  promptOptimization: () =>
    request<PromptOptimizationSnapshot>('/internal/console/prompt-optimization'),
  responsePolicy: () => request<ResponsePolicy>('/internal/console/response-policy'),
  saveResponsePolicy: (policy: ResponsePolicy) =>
    request<{ assetVersion: string; policyVersion: string; findings: Finding[] }>(
      '/internal/console/response-policy',
      {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(policy),
      },
    ),
}

export interface CacheBrowser {
  available: boolean
  message?: string
  note?: string
  truncated?: boolean
  counts?: Record<string, number>
  kinds?: Array<{ id: string; label: string; pattern: string }>
  entries: CacheEntry[]
}

export interface DecisionCacheControl {
  available: boolean
  enabled: boolean
  message?: string
}

export interface CacheEntry {
  kind: string
  kindLabel: string
  key: string
  ttlMillis?: number
  ttlLabel?: string
  bytes?: number
  value?: unknown
  valuePreview?: string | null
  truncatedValue?: boolean
  error?: string
  /** 出口决策：归一化问法（来自 decision-meta） */
  query?: string | null
  rawQuery?: string | null
  queryMissing?: boolean
  channel?: string
  page?: string
  spaceId?: string
  browseMeta?: Record<string, unknown>
}

export interface PromptOptimizationSnapshot {
  assetVersion: string
  runAvailable: boolean
  modes: PromptOptimizationMode[]
}

export interface PromptOptimizationMode {
  id: 'arbitration' | 'context-rewrite' | string
  label: string
  promptVersion: string
  promptPath: string
  currentPrompt: string
  trajectoryFile: string
  trajectoryCount: number
  trajectoryAssetVersions: string[]
  trajectoriesStale: boolean
  trajectories: PromptOptimizationTrajectory[]
  candidate: PromptOptimizationCandidate
}

export interface PromptOptimizationTrajectory {
  caseId: string
  query: string
  assetVersion: string
  truth: unknown
  conversationHistory: PromptOptimizationHistoryMessage[]
  modelInput: string
}

export interface PromptOptimizationHistoryMessage {
  role?: 'user' | 'assistant' | 'tool' | string
  ref?: string
  sourceTurnRef?: string
  content?: unknown
  [key: string]: unknown
}

export interface PromptOptimizationCandidate {
  available: boolean
  status: 'REVIEW_PENDING' | 'STALE_TRAJECTORIES' | 'NOT_GENERATED' | string
  fileName: string
  generatedAt: string | null
  version: string
  baseline: string
  score: string
  assetVersion: string
  prompt: string
  content: string
}
