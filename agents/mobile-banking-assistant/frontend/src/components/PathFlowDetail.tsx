import {
  CollaborationHop,
  CollaborationTask,
  LoopExecution,
  PathSummary,
  PlanItem,
  PlanExecution,
  RuntimeModuleStep,
} from '../api'
import {
  arbitratedByLabel,
  exitPathLabel,
  gatewayLabel,
  phaseLabel,
  shortId,
} from '../labels'

/**
 * Observation 树：分段耗时、候选、模块 I/O、协同。
 *
 * {@code variant="summary"} 带一行路径摘要（运营页轮次展开默认）。
 * {@code variant="steps"} 只渲染步骤，结论字段留给出口剖面，避免同一事实两遍。
 */
export function PathFlowDetail(props: {
  path?: PathSummary | null
  capabilityId?: string | null
  gatewayCalls?: string[]
  moduleSteps?: RuntimeModuleStep[]
  collaboration?: { delegations?: CollaborationHop[]; tasks?: CollaborationTask[] }
  planExecution?: PlanExecution
  loopExecution?: LoopExecution
  traceId?: string
  emptyHint?: string
  /** summary：路径一行摘要；steps：仅步骤树（对话页右侧用） */
  variant?: 'summary' | 'steps'
}) {
  const path = props.path
  const variant = props.variant ?? 'summary'
  const phase = path?.phaseMs ?? {}
  const phases = ['rewrite', 'recall', 'arbitration'].filter((p) => phase[p] != null)
  const candidates = arrayOrEmpty(path?.topCandidates)
  const calls = arrayOrEmpty(props.gatewayCalls)
  const moduleSteps = arrayOrEmpty(props.moduleSteps)
  const delegations = moduleSteps.filter((step) => step.module === 'a2a-client')
  const persistedDelegations = arrayOrEmpty(props.collaboration?.delegations)
  const collaborationTasks = arrayOrEmpty(props.collaboration?.tasks)
  const planSteps = arrayOrEmpty(props.planExecution?.steps)
  const cacheSteps = moduleSteps.filter((step) => step.role === 'CACHE' || step.module === 'decision-cache')
  const stateSteps = moduleSteps.filter((step) =>
    ['CONTEXT', 'MEMORY'].includes(step.role),
  )
  const pipelineSteps = moduleSteps.filter((step) =>
    step.module === 'intent-rewrite' || step.module === 'intent-recall',
  )
  const slowPathSteps = moduleSteps.filter((step) => step.module === 'intent-slowpath')
  const conditionSteps = slowPathSteps.filter((step) => step.operation === 'condition-gate')
  const blueprintSteps = slowPathSteps.filter((step) => step.operation === 'plan-blueprint')
  const planItems = arrayOrEmpty(props.planExecution?.items)
  const blueprintFromStep = blueprintItems(blueprintSteps[0])
  const blueprint = planItems.length > 0 ? planItems : blueprintFromStep
  const pipeline = path?.pipeline
  const showRuleCols = candidates.some((c) => c.rule !== 0 || c.negative !== 0)

  if (!path || path.exitPath === 'UNKNOWN') {
    return (
      <div className="path-flow-detail">
        <LoopExecutionDetail execution={props.loopExecution} />
        <div style={{ fontSize: 12, color: 'var(--muted)' }}>
          {props.emptyHint ?? '无路径摘要。'}
          {props.traceId && (
            <div className="mono" style={{ marginTop: 6 }} title={props.traceId}>
              trace {shortId(props.traceId)}
            </div>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="path-flow-detail">
      <LoopExecutionDetail execution={props.loopExecution} />
      {variant === 'summary' && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          <span className="tag ok" title={path.exitPath}>路径 {exitPathLabel(path.exitPath)}</span>
          <span className="tag muted" title={path.arbitratedBy}>
            {arbitratedByLabel(path.arbitratedBy)}
          </span>
          {path.selectedRank === 0 ? (
            <span className="tag warn">未选中任何候选</span>
          ) : path.selectedRank != null ? (
            <span className={`tag ${path.overruledTop1 ? 'warn' : 'ok'}`}>
              选中排名 #{path.selectedRank}
              {path.overruledTop1 ? ' · 推翻 top1' : ' · 与召回一致'}
            </span>
          ) : null}
          {path.margin != null && (
            <span className="tag muted" title="头两名融合分差">
              分差 {path.margin.toFixed(3)}
            </span>
          )}
        </div>
      )}

      {pipeline && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            改写链路（规则/缓存用 normalized · BM25 用 searchText · 向量/仲裁用 semanticText）
          </div>
          <div className="pipeline-io">
            <PipelineRow label="原始问句" value={pipeline.originalQuery} />
            <PipelineRow label="归一化" value={pipeline.normalizedQuery} emphasize />
            <PipelineRow label="检索文本" value={pipeline.searchText} />
            <PipelineRow label="语义文本" value={pipeline.semanticText} />
            {arrayOrEmpty(pipeline.terms).length > 0 && (
              <PipelineRow label="分词" value={pipeline.terms!.join(' · ')} mono />
            )}
            {pipeline.slots && Object.keys(pipeline.slots).length > 0 && (
              <PipelineRow label="槽位" value={JSON.stringify(pipeline.slots)} mono />
            )}
          </div>
        </div>
      )}

      {pipelineSteps.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            改写 / 召回步骤 I/O
          </div>
          <div className="runtime-state-grid">
            {pipelineSteps.map((step, index) => (
              <div key={`${step.module}-${step.operation}-${index}`} className="runtime-state-item">
                <div>
                  <b>{moduleLabel(step.module)}</b>
                  <span className="tag muted" style={{ marginLeft: 6 }}>{step.operation}</span>
                  <span className="mono" style={{ marginLeft: 8, fontSize: 11 }}>
                    {formatDuration(step.durationMs)}
                  </span>
                </div>
                <div className="mono runtime-io" style={{ marginTop: 4 }}>
                  in {formatIo(step.input)}
                </div>
                <div className="mono runtime-io">
                  out {formatIo(step.output)}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {phases.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>分段耗时</div>
          <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
            {phases.map((p) => (
              <span key={p} className="mono" style={{ fontSize: 13 }} title={p}>
                {phaseLabel(p)} <b>{phase[p]}</b>ms
              </span>
            ))}
          </div>
        </div>
      )}

      {calls.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            网关用途（{calls.length}）
          </div>
          <div className="mono" style={{ fontSize: 12 }} title={calls.join(' → ')}>
            {calls.map(gatewayLabel).join(' → ')}
          </div>
        </div>
      )}

      {delegations.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>主子协同</div>
          {delegations.map((step, index) => (
            <div key={`${step.operation}-${index}`} className="coordination-flow">
              <span className="mono">{String(step.input.sourceAgent ?? '主 Agent')}</span>
              <span aria-hidden="true">→</span>
              <span>A2A Gateway</span>
              <span aria-hidden="true">→</span>
              <span className="mono">{String(step.input.targetAgent ?? '子 Agent')}</span>
              <span className={`tag ${step.outcome === 'SUCCEEDED' ? 'ok' : 'warn'}`}>
                {step.outcome}
              </span>
              <span className="mono">{formatDuration(step.durationMs)}</span>
            </div>
          ))}
        </div>
      )}

      {persistedDelegations.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            协同链（台账）
          </div>
          {persistedDelegations.map((hop) => (
            <div key={hop.delegationId} className="coordination-flow">
              <span className="mono">{hop.sourceAgentId}</span>
              <span aria-hidden="true">→</span><span>A2A Gateway</span><span aria-hidden="true">→</span>
              <span className="mono">{hop.targetAgentId}</span>
              <span className="tag muted">{hop.mode} · depth {hop.depth}</span>
              <span className={`tag ${hop.outcome === 'SUCCEEDED' ? 'ok' : 'warn'}`}>
                {hop.outcome ?? 'RUNNING'}
              </span>
            </div>
          ))}
        </div>
      )}

      {collaborationTasks.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            各层任务（{collaborationTasks.length}）
          </div>
          <div className="runtime-state-grid">
            {collaborationTasks.map((task, index) => (
              <div key={task.taskId} className="runtime-state-item">
                <div>
                  <b>{index + 1}. <span className="mono">{task.agentId}</span></b>
                  <span className={`tag ${task.state === 'SUCCEEDED' ? 'ok' : 'warn'}`} style={{ marginLeft: 6 }}>
                    {task.state}
                  </span>
                </div>
                <div className="mono runtime-io" title={task.taskId}>
                  task {shortId(task.taskId)} · {task.capabilityId ?? '—'}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {(blueprint.length > 0 || planSteps.length > 0 || conditionSteps.length > 0) && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            Slow Path
            {props.planExecution?.source ? ` · ${props.planExecution.source}` : ''}
            {' · '}
            游标 {props.planExecution?.cursor ?? 0}/
            {props.planExecution?.stepCount
              ?? (blueprint.length || planSteps.length)}
            {props.planExecution?.state ? ` · ${props.planExecution.state}` : ''}
          </div>
          {props.planExecution?.original && (
            <div className="mono runtime-io" style={{ marginBottom: 8 }} title={props.planExecution.original}>
              原话 {props.planExecution.original}
            </div>
          )}
          {blueprint.length > 0 && (
            <div className="pipeline-io" style={{ marginBottom: planSteps.length || conditionSteps.length ? 10 : 0 }}>
              <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 4 }}>计划蓝图</div>
              {blueprint.map((item, index) => {
                const order = item.order ?? index
                const atCursor = props.planExecution?.cursor === order
                const resolution = planResolution(item)
                return (
                  <div key={`${order}-${item.capabilityId ?? item.text ?? index}`} className="pipeline-io-row">
                    <span className="pipeline-io-label">
                      {atCursor ? '▸' : ''}{order + 1}
                    </span>
                    <span>
                      <b>{item.summary || item.text || '—'}</b>
                      {item.capabilityId && (
                        <span className="mono" style={{ marginLeft: 8, color: 'var(--muted)' }}>
                          {item.capabilityId}
                        </span>
                      )}
                      {item.relation && (
                        <span className="tag muted" style={{ marginLeft: 6 }}>{item.relation}</span>
                      )}
                      {resolution.strength && (
                        <span
                          className={`tag ${resolution.strength === 'LOCKED' ? 'ok' : 'muted'}`}
                          style={{ marginLeft: 6 }}
                        >
                          {resolution.strength}
                        </span>
                      )}
                      {(resolution.topScore != null || resolution.margin != null) && (
                        <span className="mono" style={{ marginLeft: 8, color: 'var(--muted)', fontSize: 11 }}>
                          Top1 {formatScore(resolution.topScore)} · 分差 {formatScore(resolution.margin)}
                        </span>
                      )}
                      {item.condition && (
                        <div className="mono runtime-io" style={{ marginTop: 2 }}>
                          条件「{item.condition}」
                        </div>
                      )}
                      {item.text && item.summary && item.text !== item.summary && (
                        <div className="mono runtime-io" style={{ marginTop: 2 }}>{item.text}</div>
                      )}
                      {resolution.candidateIds.length > 0 && (
                        <div className="mono runtime-io" style={{ marginTop: 2 }}>
                          候选 {resolution.candidateIds.join(' · ')}
                        </div>
                      )}
                      {resolution.evidenceRefs.length > 0 && (
                        <div className="mono runtime-io" style={{ marginTop: 2 }}>
                          证据 {resolution.evidenceRefs.join(' · ')}
                        </div>
                      )}
                    </span>
                  </div>
                )
              })}
            </div>
          )}
          {conditionSteps.length > 0 && (
            <div className="runtime-state-grid" style={{ marginBottom: planSteps.length ? 10 : 0 }}>
              {conditionSteps.map((step, index) => (
                <div key={`condition-${index}`} className="runtime-state-item">
                  <div>
                    <b>条件闸门</b>
                    <span
                      className={`tag ${step.output?.verdict === 'PROCEED' ? 'ok' : 'warn'}`}
                      style={{ marginLeft: 6 }}
                    >
                      {String(step.output?.verdict ?? step.outcome)}
                    </span>
                  </div>
                  <div className="mono runtime-io">
                    {step.input?.condition
                      ? `「${String(step.input.condition)}」`
                      : '—'}
                    {step.output?.balance != null && String(step.output.balance) !== ''
                      ? ` · 余额 ${String(step.output.balance)}`
                      : ''}
                    {step.output?.amount != null && String(step.output.amount) !== ''
                      ? ` · 金额 ${String(step.output.amount)}`
                      : ''}
                    {step.output?.reason
                      ? ` · ${String(step.output.reason)}`
                      : ''}
                  </div>
                </div>
              ))}
            </div>
          )}
          {planSteps.length > 0 && (
            <div className="runtime-state-grid">
              {planSteps.map((step) => (
                <div key={`${props.planExecution?.planId}-${step.stepIndex}`} className="runtime-state-item">
                  <b>{step.stepIndex + 1}. {step.capabilityId}</b>
                  <span className={`tag ${step.status === 'SUCCESS' ? 'ok' : 'warn'}`} style={{ marginLeft: 6 }}>
                    {step.status}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {cacheSteps.length > 0 && (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            出口缓存（decision-cache · Redis）
          </div>
          <div className="runtime-state-grid">
            {cacheSteps.map((step, index) => (
              <div key={`${step.module}-${step.operation}-${index}`} className="runtime-state-item">
                <div>
                  <b>{step.operation === 'read' ? '读' : step.operation === 'write' ? '写' : step.operation}</b>
                  <span
                    className={`tag ${cacheHit(step) ? 'ok' : step.outcome === 'OK' ? 'muted' : 'warn'}`}
                    style={{ marginLeft: 6 }}
                  >
                    {cacheResultLabel(step)}
                  </span>
                </div>
                <div className="mono runtime-io">{formatIo(step.output)}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {stateSteps.length > 0 && (
        <details>
          <summary style={{ fontSize: 12, color: 'var(--muted)', cursor: 'pointer' }}>
            上下文 / 记忆（{stateSteps.length}）
          </summary>
          <div className="runtime-state-grid" style={{ marginTop: 8 }}>
            {stateSteps.map((step, index) => (
              <div key={`${step.module}-${step.operation}-${index}`} className="runtime-state-item">
                <div>
                  <b>{moduleLabel(step.module)}</b>
                  <span className={`tag ${step.outcome === 'OK' ? 'ok' : 'warn'}`} style={{ marginLeft: 6 }}>
                    {step.outcome}
                  </span>
                </div>
                <div className="mono runtime-io">{formatIo(step.output)}</div>
              </div>
            ))}
          </div>
        </details>
      )}

      {moduleSteps.length > 0 && (
        <details>
          <summary style={{ fontSize: 12, color: 'var(--muted)', cursor: 'pointer' }}>
            模块 I/O（{moduleSteps.length} 步，已脱敏）
          </summary>
          <div className="module-table-scroll" style={{ marginTop: 8 }}>
            <table className="module-io-table">
              <thead>
                <tr>
                  <th>#</th><th>角色</th><th>模块 / 操作</th><th>输入</th><th>输出</th><th>结果</th><th>耗时</th>
                </tr>
              </thead>
              <tbody>
                {moduleSteps.map((step, index) => (
                  <tr key={`${step.module}-${step.operation}-${index}`}>
                    <td className="mono">{index + 1}</td>
                    <td>{roleLabel(step.role)}</td>
                    <td>
                      <b>{moduleLabel(step.module)}</b>
                      <div className="mono">{step.operation}</div>
                    </td>
                    <td className="mono runtime-io">{formatIo(step.input)}</td>
                    <td className="mono runtime-io">{formatIo(step.output)}</td>
                    <td>
                      <span className={`tag ${successOutcome(step.outcome) ? 'ok' : 'warn'}`}>
                        {step.outcome}
                      </span>
                    </td>
                    <td className="mono">{formatDuration(step.durationMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}

      {candidates.length > 0 ? (
        <div>
          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
            Top 候选（★ 仲裁选中）
            {!showRuleCols && ' · rule/neg 全零已收起'}
          </div>
          <table>
            <thead>
              <tr>
                <th>#</th><th>能力</th><th>融合</th><th>语义</th>
                {showRuleCols && <><th>规则</th><th>负向</th></>}
              </tr>
            </thead>
            <tbody>
              {candidates.map((c, i) => {
                const rank = i + 1
                const selected = path.selectedRank === rank
                  || c.candidateId === props.capabilityId
                return (
                  <tr key={c.candidateId} style={selected ? { color: 'var(--ok)' } : undefined}>
                    <td className="mono">{rank}{selected ? ' ★' : ''}</td>
                    <td className="mono">{c.candidateId}</td>
                    <td className="mono">{c.fusedScore.toFixed(3)}</td>
                    <td className="mono">{c.semantic.toFixed(3)}</td>
                    {showRuleCols && (
                      <>
                        <td className="mono">{c.rule.toFixed(3)}</td>
                        <td className="mono">{c.negative.toFixed(3)}</td>
                      </>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      ) : (
        <div style={{ fontSize: 12, color: 'var(--muted)' }}>
          无召回候选（短路或召回为空）。
        </div>
      )}

      {props.traceId && (
        <div className="mono" style={{ fontSize: 12, color: 'var(--muted)' }} title={props.traceId}>
          trace {shortId(props.traceId)}
          {' · '}
          <a href={`http://localhost:16686/trace/${props.traceId}`} target="_blank" rel="noreferrer">
            Jaeger
          </a>
        </div>
      )}
    </div>
  )
}

function LoopExecutionDetail({ execution }: { execution?: LoopExecution }) {
  if (!execution) return null
  return (
    <div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', marginBottom: 8 }}>
        <b>Agent Loop</b>
        <span className={`tag ${runtimeTone(execution.status)}`} title={execution.status}>
          {runtimeStatusLabel(execution.status)}
        </span>
        <span className="mono" title={execution.loopId}>{shortId(execution.loopId)}</span>
        <span className="mono">轮次 {execution.iteration}/{execution.maxIterations}</span>
        {execution.reasonCode && (
          <span className="tag warn" title={execution.reasonCode}>{execution.reasonCode}</span>
        )}
      </div>
      <div className="runtime-state-grid">
        {arrayOrEmpty(execution.steps).map((step) => (
          <div key={`${execution.loopId}-${step.stepIndex}`} className="runtime-state-item">
            <div>
              <b>{step.stepIndex + 1}. {loopActionLabel(step.actionType)}</b>
              {step.targetId && <span className="mono" style={{ marginLeft: 8 }}>{step.targetId}</span>}
              <span className={`tag ${runtimeTone(step.status)}`} style={{ marginLeft: 6 }}>
                {runtimeStatusLabel(step.status)}
              </span>
            </div>
            {step.observation && (
              <div className="mono runtime-io" style={{ marginTop: 4 }}>
                Observation {step.observation.status}
                {step.observation.failureClass ? ` · ${step.observation.failureClass}` : ''}
                {step.observation.retryable ? ' · 可重试' : ''}
                {step.observation.reasonCode ? ` · ${step.observation.reasonCode}` : ''}
              </div>
            )}
            {step.proposalReasonCode && (
              <div className="mono runtime-io">Proposal {step.proposalReasonCode}</div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function loopActionLabel(action?: string | null): string {
  return ({
    CALL_CAPABILITY: '调用能力',
    SEARCH_KNOWLEDGE: '检索知识',
    RESOLVE_MENU: '解析菜单',
    DELEGATE_GOAL: '委托 Agent',
    ASK_USER: '询问用户',
    FINISH: '完成',
    HANDOFF: '转人工',
  } as Record<string, string>)[action ?? ''] ?? action ?? '未知动作'
}

function runtimeStatusLabel(status?: string | null): string {
  return ({
    NEW: '新建', RUNNING: '执行中', WAITING_USER: '等待用户',
    WAITING_REVIEW: '等待确认', WAITING_CONFIRMATION: '等待确认',
    COMPLETED: '已完成', FAILED: '失败', HANDED_OFF: '已转人工',
    EXPIRED: '已过期', CANCELLED: '已取消', PROPOSED: '已提议',
    CLAIMED: '执行中', UNKNOWN_OUTCOME: '结果未知', SUCCESS: '成功',
  } as Record<string, string>)[status ?? ''] ?? status ?? '—'
}

function runtimeTone(status?: string | null): string {
  if (['COMPLETED', 'SUCCESS'].includes(status ?? '')) return 'ok'
  if (['FAILED', 'HANDED_OFF', 'UNKNOWN_OUTCOME', 'EXPIRED'].includes(status ?? '')) return 'warn'
  return 'muted'
}

function PipelineRow(props: {
  label: string
  value?: string | null
  emphasize?: boolean
  mono?: boolean
}) {
  const text = props.value == null || props.value === '' ? '—' : props.value
  return (
    <div className="pipeline-io-row">
      <span className="pipeline-io-label">{props.label}</span>
      <span
        className={props.mono || props.emphasize ? 'mono' : undefined}
        style={props.emphasize ? { color: 'var(--ok)', fontWeight: 600 } : undefined}
        title={text}
      >
        {text}
      </span>
    </div>
  )
}

function formatIo(value: Record<string, unknown>): string {
  if (value == null || typeof value !== 'object') return '—'
  return Object.entries(value)
    .map(([key, item]) => `${key}=${formatValue(item)}`)
    .join(' · ') || '—'
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) {
    if (value.every((item) => item == null || typeof item !== 'object')) {
      return `[${value.map(formatValue).join(', ')}]`
    }
    return JSON.stringify(value)
  }
  if (value != null && typeof value === 'object') return JSON.stringify(value)
  return String(value ?? '—')
}

function blueprintItems(step?: RuntimeModuleStep): PlanItem[] {
  const raw = step?.output?.items
  return Array.isArray(raw) ? raw as PlanItem[] : []
}

function planResolution(item: PlanItem) {
  return {
    strength: item.resolutionStrength ?? item.resolution?.strength,
    topScore: item.topScore ?? item.resolution?.topScore,
    margin: item.margin ?? item.resolution?.margin,
    candidateIds: arrayOrEmpty(item.candidateIds ?? item.resolution?.candidateIds),
    evidenceRefs: arrayOrEmpty(item.evidenceRefs ?? item.resolution?.evidenceRefs),
  }
}

function formatScore(value?: number): string {
  return typeof value === 'number' ? value.toFixed(3) : '—'
}

function moduleLabel(module: string): string {
  return ({
    'context-engine': '全局上下文租约',
    'conversation-memory': '会话记忆',
    'working-memory': '工作记忆',
    'decision-cache': '决策缓存',
    'intent-engine': '意图引擎',
    'intent-rewrite': '查询改写',
    'intent-recall': '混合召回',
    'intent-slowpath': '慢路径',
    'task-orchestrator': '任务中控',
    'a2a-client': 'A2A 委托',
    'response-engine': '回复引擎',
  } as Record<string, string>)[module] ?? module
}

function roleLabel(role: string): string {
  return ({ MAIN: '主 Agent', CHILD: '子 Agent', CONTEXT: '上下文', MEMORY: '记忆', CACHE: '缓存' } as Record<string, string>)[role] ?? role
}

function formatDuration(value: number | null): string {
  return value == null ? '—' : `${value}ms`
}

function successOutcome(value: string): boolean {
  return ['OK', 'SUCCEEDED', 'SUCCESS', 'HIT'].includes(value)
}

function arrayOrEmpty<T>(value: T[] | undefined | null): T[] {
  return Array.isArray(value) ? value : []
}

function cacheHit(step: RuntimeModuleStep): boolean {
  return String(step.output?.result ?? '') === 'HIT'
}

function cacheResultLabel(step: RuntimeModuleStep): string {
  const result = String(step.output?.result ?? step.output?.storedDecision ?? step.outcome ?? '—')
  if (result === 'HIT') return '命中'
  if (result === 'MISS') return '未命中'
  if (result === 'FALLBACK') return '读失败按未命中'
  if (step.operation === 'write' && step.outcome === 'OK') return `已写入 ${result}`
  return result
}
