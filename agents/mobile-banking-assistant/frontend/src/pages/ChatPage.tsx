import { useEffect, useRef, useState } from 'react'
import { api, ChatResponse, ConsoleSettings, RecentEntry, ResponseComponent, Tenant } from '../api'
import { PathFlowDetail } from '../components/PathFlowDetail'
import { TurnExecutionTrace } from '../components/TurnExecutionTrace'
import { clientActionOutcome, ConversationExperience } from '../components/ConversationExperience'
import {
  decisionLabel,
  reasonLabel,
  shortCircuitLabel,
  shortId,
} from '../labels'

interface Turn {
  id: string
  role: 'user' | 'bot'
  text: string
  response?: ChatResponse
  error?: string
  streaming?: boolean
}

/**
 * 对话页。右侧固定摆着出口剖面。
 *
 * 流转明细从观测体系 {@code /internal/console/recent} 按 traceId 取，不改 chat 应答契约。
 */
export function ChatPage({ tenant, settings, agent }: {
  tenant: Tenant
  settings: ConsoleSettings['chat']
  agent: ConsoleSettings['agent']
}) {
  const [turns, setTurns] = useState<Turn[]>([])
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [page, setPage] = useState(settings.defaultPage)
  const [userState, setUserState] = useState(settings.defaultUserState)
  const [sessionId, setSessionId] = useState(() => createSessionId(settings.sessionPrefix))
  const [selected, setSelected] = useState<ChatResponse | null>(null)
  const [observations, setObservations] = useState<Record<string, RecentEntry>>({})
  const [submittedByTrace, setSubmittedByTrace] = useState<Record<string, string>>({})
  const [clientOutcomes, setClientOutcomes] = useState<Record<string, string>>({})
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const traceIds = turns.flatMap((turn) => turn.response?.traceId ? [turn.response.traceId] : [])
    let pending = traceIds.filter((traceId) => !observations[traceId])
    if (pending.length === 0) return
    let cancelled = false
    const poll = async () => {
      for (let attempt = 0; attempt < 6 && pending.length > 0 && !cancelled; attempt += 1) {
        try {
          const rows = await api.recent()
          if (cancelled) return
          const found = Object.fromEntries(
            rows.filter((row) => pending.includes(row.traceId)).map((row) => [row.traceId, row]),
          )
          if (Object.keys(found).length > 0) {
            setObservations((current) => ({ ...current, ...found }))
            pending = pending.filter((traceId) => !found[traceId])
          }
        } catch {
          // Observation is supplemental; a transient console read must not fail the chat turn.
        }
        if (pending.length > 0 && attempt < 5) await delay(400)
      }
    }
    void poll()
    return () => { cancelled = true }
  }, [turns, observations])

  async function send(
    action?: { event: string; ref: string; version: number; label: string },
    quickReply?: string,
    sourceTraceId?: string,
  ) {
    const query = action ? action.label : quickReply ?? input.trim()
    if ((!query && !action) || busy) return
    if (sourceTraceId) {
      setSubmittedByTrace((current) => ({
        ...current,
        [sourceTraceId]: query,
      }))
    }
    setInput('')
    const requestId = `${Date.now()}-${Math.random().toString(36).slice(2)}`
    const botId = `bot-${requestId}`
    setTurns((t) => [...t,
      { id: `user-${requestId}`, role: 'user', text: query },
      { id: botId, role: 'bot', text: '', streaming: true },
    ])
    setBusy(true)
    const revealed: ResponseComponent[] = []
    let visibleResultCardCount = 0
    let streamFailed = false
    try {
      await api.chatStream(query, sessionId, tenant, page, userState, action, (event) => {
        if (event.type === 'CARD_AVAILABLE' && event.response && event.component) {
          if (!revealed.includes(event.component)) revealed.push(event.component)
          if (event.component === 'RESULT_SUMMARY' && event.itemIndex != null) {
            visibleResultCardCount = Math.max(visibleResultCardCount, event.itemIndex + 1)
          }
          const partial = projectResponse(event.response, revealed, false, visibleResultCardCount)
          setTurns((current) => current.map((turn) => turn.id === botId
            ? { ...turn, response: partial, streaming: true }
            : turn))
          requestAnimationFrame(() => listRef.current?.scrollTo(0, listRef.current.scrollHeight))
          return
        }
        if (event.type === 'TURN_COMPLETED' && event.response) {
          const response = projectResponse(event.response,
            event.response.plan?.cardComponents ?? revealed, true)
          setTurns((current) => current.map((turn) => turn.id === botId
            ? { ...turn, text: response.text, response, streaming: false }
            : turn))
          setSelected(response)
          return
        }
        if (event.type === 'TURN_FAILED') {
          streamFailed = true
          setTurns((current) => current.map((turn) => turn.id === botId
            ? { ...turn, streaming: false, error: event.message ?? '本轮处理失败' }
            : turn))
        }
      })
    } catch (e) {
      if (sourceTraceId) {
        setSubmittedByTrace((current) => {
          const next = { ...current }
          delete next[sourceTraceId]
          return next
        })
      }
      if (!streamFailed) {
        setTurns((current) => current.map((turn) => turn.id === botId
          ? { ...turn, streaming: false, error: String(e) }
          : turn))
      }
    } finally {
      setBusy(false)
      requestAnimationFrame(() => listRef.current?.scrollTo(0, listRef.current.scrollHeight))
    }
  }

  return (
    <div className="chat-layout">
      <div className="panel chat-main">
        <div className="chat-parameters">
          <label>会话
            <input
              value={sessionId}
              onChange={(e) => setSessionId(e.target.value)}
              title="会话标识。换一个等于开一段新对话，未办完的任务与多意图计划都不再续办"
            />
          </label>
          <label>页面
            <select value={page} onChange={(e) => setPage(e.target.value)} title="页面上下文，参与出口缓存键">
              {settings.pages.map((option) => <option key={option} value={option}>{option}</option>)}
            </select>
          </label>
          <label>用户状态
            <select value={userState} onChange={(e) => setUserState(e.target.value)}>
              {settings.userStates.map((option) => <option key={option} value={option}>{option}</option>)}
            </select>
          </label>
          <div className="parameter-spacer" />
          <button className="ghost" onClick={() => {
            setTurns([])
            setSelected(null)
            setObservations({})
            setSubmittedByTrace({})
            setClientOutcomes({})
            setSessionId(createSessionId(settings.sessionPrefix))
          }}>
            新会话
          </button>
        </div>

        <div className="messages" ref={listRef}>
          {turns.length === 0 && (
            <div className="empty">
              试试 {settings.exampleQueries.map((query) => `「${query}」`).join('、')}。
            </div>
          )}
          {turns.map((turn) => (
            <div
              key={turn.id}
              className={`msg ${turn.role}${turn.streaming ? ' streaming' : ''}`}
              onClick={() => turn.response && setSelected(turn.response)}
              style={{ cursor: turn.response ? 'pointer' : 'default' }}
            >
              {turn.error ? <span style={{ color: 'var(--danger)' }}>{turn.error}</span>
                : turn.streaming && !turn.response
                  ? <span className="response-pending" role="status" aria-live="polite">
                      <span className="pending-dot" aria-hidden="true" />正在处理
                    </span>
                  : turn.text}
              {turn.response && (
                <>
                <TurnExecutionTrace
                  response={turn.response}
                  observation={observations[turn.response.traceId]}
                  entryAgent={agent}
                />
                <ConversationExperience
                  response={turn.response}
                  observation={observations[turn.response.traceId]}
                  busy={busy}
                  inferMissing={!turn.streaming}
                  submittedLabel={submittedByTrace[turn.response.traceId]}
                  clientOutcome={clientOutcomes[turn.response.traceId]}
                  onAction={(action) => void send(action, undefined, turn.response?.traceId)}
                  onQuickReply={(text) => void send(undefined, text, turn.response?.traceId)}
                  onClientAction={(code) => {
                    if (code === 'RETRY') {
                      void send(undefined, '重试', turn.response?.traceId)
                      return
                    }
                    const outcome = clientActionOutcome(code, turn.response as ChatResponse)
                    setClientOutcomes((current) => ({ ...current, [turn.response!.traceId]: outcome }))
                  }}
                />
                </>
              )}
            </div>
          ))}
        </div>

        <div className="composer">
          <input
            value={input}
            placeholder="说点什么…"
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && send()}
          />
          <button className="action" onClick={() => void send()} disabled={busy}>
            {busy ? '…' : '发送'}
          </button>
        </div>
      </div>

      <ExitProfile response={selected} obs={selected ? observations[selected.traceId] ?? null : null} />
    </div>
  )
}

export function projectResponse(
  response: ChatResponse,
  components: ResponseComponent[],
  complete: boolean,
  visibleResultCardCount = 0,
): ChatResponse {
  const slots = { ...(response.plan?.slots ?? {}) }
  if (!complete && visibleResultCardCount > 0 && Array.isArray(slots.resultCards)) {
    slots.resultCards = slots.resultCards.slice(0, visibleResultCardCount)
  }
  return {
    ...response,
    // The answer is already audited when the server starts projecting cards. Showing it with
    // the first card keeps existing cards from shifting down when TURN_COMPLETED arrives.
    text: response.text,
    plan: {
      ...response.plan,
      slots,
      cardComponents: [...components],
      actionCodes: complete ? response.plan?.actionCodes : [],
    },
    actions: complete ? response.actions : [],
  }
}

function createSessionId(prefix: string) {
  return `${prefix}-${Date.now()}`
}

function delay(millis: number) {
  return new Promise((resolve) => window.setTimeout(resolve, millis))
}

function ExitProfile({ response, obs }: { response: ChatResponse | null; obs: RecentEntry | null }) {
  if (!response) {
    return (
      <div className="panel">
        <h3>本轮结论</h3>
        <div className="empty">发一句话，或点上面任意一条回复。</div>
      </div>
    )
  }

  const d = response.decision
  const degraded = response.degradedChannels ?? []
  return (
    <div className="panel" style={{ overflow: 'auto' }}>
      <h3>本轮结论</h3>
      <div className="kv">
        <span className="k">出口</span>
        <span className="v">
          <span className={`tag ${d.decision}`} title={d.decision}>{decisionLabel(d.decision)}</span>
          {response.fellBack && <span className="tag warn" style={{ marginLeft: 6 }}>兜底</span>}
        </span>
        <span className="k">原因</span>
        <span className="v" title={d.reasonCode ?? undefined}>{reasonLabel(d.reasonCode)}</span>
        {d.shortCircuit && d.shortCircuit !== 'NONE' && (
          <>
            <span className="k">短路</span>
            <span className="v">
              <span
                className={`tag ${d.shortCircuit === 'L1_CACHE' ? 'ok' : 'muted'}`}
                title={d.shortCircuit}
              >
                {shortCircuitLabel(d.shortCircuit)}
              </span>
            </span>
          </>
        )}
        <span className="k">能力</span>
        <span className="v">{d.candidateIds?.join('、') || '—'}</span>
        <span className="k">置信</span>
        <span className="v">{d.confidence?.toFixed(3) ?? '—'}</span>
        <span className="k">缺槽</span>
        <span className="v">{d.missingSlots?.length ? d.missingSlots.join('、') : '—'}</span>
        <span className="k">耗时</span>
        <span className="v">{obs?.elapsedMillis != null ? `${obs.elapsedMillis}ms` : '—'}</span>
        {degraded.length > 0 && (
          <>
            <span className="k">降级</span>
            <span className="v">
              {degraded.map((c) => (
                <span key={c} className="tag warn" style={{ marginRight: 4 }}>{c}</span>
              ))}
            </span>
          </>
        )}
        <span className="k">trace</span>
        <span className="v" title={response.traceId}>
          {shortId(response.traceId)}
          {' · '}
          <a href={`http://localhost:16686/trace/${response.traceId}`} target="_blank" rel="noreferrer">
            Jaeger
          </a>
        </span>
      </div>

      <h3 style={{ marginTop: 18 }}>Observation</h3>
      <PathFlowDetail
        path={obs?.path}
        capabilityId={obs?.capabilityId ?? d.candidateIds?.[0] ?? null}
        gatewayCalls={obs?.gatewayCalls}
        moduleSteps={obs?.moduleSteps}
        collaboration={obs?.collaboration}
        planExecution={obs?.planExecution}
        traceId={response.traceId}
        variant="steps"
        emptyHint="观测 recent 里还没有这条 trace（稍候刷新，或到会话页查看）。"
      />

      {d.evidenceRefs?.length > 0 && (
        <details style={{ marginTop: 18 }}>
          <summary style={{ cursor: 'pointer', fontSize: 13 }}>证据</summary>
          <div className="mono" style={{ fontSize: 12, color: 'var(--muted)', marginTop: 8 }}>
            {d.evidenceRefs.join(' · ')}
          </div>
        </details>
      )}

      <details style={{ marginTop: 12 }}>
        <summary style={{ cursor: 'pointer', fontSize: 13 }}>
          回复计划 / 版本
        </summary>
        <div className="mono" style={{ fontSize: 12, color: 'var(--muted)', marginTop: 8 }}>
          模型 {d.modelVersion} · 提示词 {d.promptVersion} · 资产 {d.configVersion}
          {response.usedTemplate ? ` · 模板 ${response.usedTemplate}` : ''}
          {response.taskId ? ` · task ${shortId(response.taskId)}` : ''}
        </div>
        <pre className="mono" style={{ margin: '8px 0 0', whiteSpace: 'pre-wrap', color: 'var(--muted)' }}>
          {JSON.stringify(response.plan, null, 2)}
        </pre>
      </details>
    </div>
  )
}
