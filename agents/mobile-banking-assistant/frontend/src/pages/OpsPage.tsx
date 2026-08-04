import { Fragment, useEffect, useState, type ReactNode } from 'react'
import {
  api,
  AgentDirectory,
  CacheBrowser,
  CacheEntry,
  ConsoleSettings,
  DecisionCacheControl,
  Overview,
  RecentEntry,
} from '../api'
import { PathFlowDetail } from '../components/PathFlowDetail'
import {
  decisionLabel,
  exitPathLabel,
  gatewayLabel,
  metricKeyLabel,
  phaseLabel,
  reasonLabel,
  shortId,
} from '../labels'
import { formatOperationsTime, operationsTimeMillis } from '../time'

/**
 * 运营观测页。
 *
 * 结构：告警与模块状态 → 聚合指标 → 最近会话 → Redis 缓存。
 * 空指标仍展示占位，避免「页面像被掏空」；深链路外链 Jaeger / Grafana。
 */
export function OpsPage({
  overview,
  operations,
}: {
  overview: Overview | null
  operations: ConsoleSettings['operations']
}) {
  const [metrics, setMetrics] = useState<Record<string, any> | null>(null)
  const [recent, setRecent] = useState<RecentEntry[]>([])
  const [agents, setAgents] = useState<AgentDirectory | null>(null)
  const [fusion, setFusion] = useState<Record<string, any> | null>(null)
  const [cache, setCache] = useState<CacheBrowser | null>(null)
  const [decisionCacheControl, setDecisionCacheControl] = useState<DecisionCacheControl | null>(null)
  const [cacheKind, setCacheKind] = useState<string>('decision')
  const [expandedCacheKey, setExpandedCacheKey] = useState<string | null>(null)
  const [auto, setAuto] = useState(operations.autoRefreshEnabled)
  const [expandedSessions, setExpandedSessions] = useState<Set<string>>(() => new Set())
  const [expandedTurn, setExpandedTurn] = useState<string | null>(null)

  useEffect(() => {
    const load = () => {
      api.metrics().then(setMetrics).catch(() => {})
      api.recent().then(setRecent).catch(() => {})
      api.agents().then(setAgents).catch(() => {})
      api.rules().then((r) => setFusion(r.fusion ?? null)).catch(() => {})
      api.cache(cacheKind || undefined).then(setCache).catch(() => setCache(null))
      api.decisionCacheControl().then(setDecisionCacheControl).catch(() => setDecisionCacheControl(null))
    }
    load()
    if (!auto) return
    const timer = setInterval(load, operations.refreshIntervalMillis)
    return () => clearInterval(timer)
  }, [auto, operations.refreshIntervalMillis, cacheKind])

  const alarms: Record<string, number> = metrics?.alarms ?? {}
  const firing = Object.entries(alarms).filter(([, v]) => v > 0)
  const channels = fusion?.channels ?? {}
  const recentSessions = groupRecentBySession(recent)

  const toggleSession = (sessionId: string) => {
    setExpandedSessions((current) => {
      const next = new Set(current)
      if (next.has(sessionId)) next.delete(sessionId)
      else next.add(sessionId)
      return next
    })
  }

  return (
    <>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16, gap: 12, flexWrap: 'wrap' }}>
        <label style={{ fontSize: 13, color: 'var(--muted)' }}>
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} />
          {' '}每 {formatInterval(operations.refreshIntervalMillis)} 刷新
        </label>
        <div style={{ flex: 1 }} />
        <span className="mono" style={{ color: 'var(--muted)', fontSize: 12 }}>
          资产 {overview?.assetVersion ?? '—'} · 索引 {overview?.index.state ?? '—'}
          {overview?.index.stale && ' · 待重建'}
        </span>
        <a className="mono" style={{ fontSize: 12 }} href="http://localhost:16686/" target="_blank" rel="noreferrer">
          Jaeger
        </a>
        <a className="mono" style={{ fontSize: 12 }} href="http://localhost:3000/" target="_blank" rel="noreferrer">
          Grafana
        </a>
      </div>

      {firing.length > 0 ? (
        <div className="banner danger">
          需要有人看：
          {firing.map(([k, v]) => (
            <span key={k} className="tag danger" style={{ marginLeft: 6 }}>
              {alarmLabel(k)} {v}
            </span>
          ))}
        </div>
      ) : (
        <div className="banner ok">预算、上下文降级、副作用拦截、答案审核、租户头——五项均为零。</div>
      )}

      <div className="panel" style={{ marginBottom: 16 }}>
        <h3>模块状态</h3>
        <div className="grid cols-4">
          <ModuleCard
            title="索引 / OpenSearch"
            tone={overview?.index.searchable ? (overview.index.stale ? 'warn' : 'ok') : 'danger'}
            lines={[
              `状态 ${overview?.index.state ?? '—'}`,
              `文档 ${overview?.index.documentCount ?? '—'}`,
              `语义 ${overview?.index.semanticAvailable ? '可用' : '不可用'}`,
              overview?.index.stale ? '与资产版本不一致' : '与资产版本一致',
            ]}
          />
          <ModuleCard
            title="召回通道（fusion）"
            tone={
              fusion == null
                ? 'warn'
                : channels.bm25Enabled || channels.semanticEnabled || channels.ruleEnabled
                  ? 'ok'
                  : 'warn'
            }
            lines={[
              `BM25 ${onOff(channels.bm25Enabled)}`,
              `语义 ${onOff(channels.semanticEnabled)}`,
              `规则 ${onOff(channels.ruleEnabled)}`,
              `重排 ${onOff(channels.rerankEnabled)}`,
            ]}
          />
          <ModuleCard
            title="智能体 / Nacos"
            tone={agents?.registry?.enabled ? 'ok' : 'warn'}
            lines={[
              `已配置 ${agents?.summary?.configured ?? 0} 个`,
              `在线 ${agents?.summary?.online ?? 0} 个`,
              `未实现 ${agents?.summary?.scaffold ?? 0} 个`,
              agents?.registry?.enabled
                ? `注册中心 ${(agents?.registry?.instances ?? []).length} 实例`
                : 'Nacos 发现未启用',
            ]}
          />
          <ModuleCard
            title="基础设施"
            tone="ok"
            lines={[
              'Postgres / Redis → Actuator',
              <a key="h" href="/actuator/health" target="_blank" rel="noreferrer">
                /actuator/health
              </a>,
              <a key="p" href="/actuator/prometheus" target="_blank" rel="noreferrer">
                /actuator/prometheus
              </a>,
            ]}
          />
        </div>
      </div>

      <div className="grid cols-4" style={{ marginBottom: 16 }}>
        <Stat label="能力卡" value={overview?.capabilityCount} />
        <Stat label="强规则" value={overview?.strongRuleCount} />
        <Stat label="索引文档" value={overview?.index.documentCount} />
        <Stat
          label="语义通道"
          value={overview?.index.semanticAvailable ? '可用' : '不可用'}
          danger={!overview?.index.semanticAvailable}
        />
      </div>

      <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <div className="panel">
          <h3>出口分布</h3>
          <Bars data={metrics?.exits} />
        </div>
        <div className="panel">
          <h3>短路层级</h3>
          <Bars data={metrics?.shortCircuit} />
          <h3 style={{ marginTop: 16 }}>降级原因</h3>
          <Bars data={metrics?.degraded} />
        </div>
      </div>

      <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <div className="panel">
          <h3>原因码</h3>
          <Bars data={metrics?.reasons} />
        </div>
        <div className="panel">
          <h3>分阶段耗时</h3>
          <table>
            <thead>
              <tr>
                <th>阶段</th><th>次数</th><th>均值 ms</th>
                <th title="Micrometer max 是滑动窗口，静置后回落为 0">最大 ms（近窗）</th>
              </tr>
            </thead>
            <tbody>
              {(metrics?.phaseLatency ?? []).map((row: any, i: number) => (
                <tr key={i}>
                  <td className="mono" title={row.key}>{row.key ? phaseLabel(row.key) : '总计'}</td>
                  <td className="mono">{row.count}</td>
                  <td className="mono">{row.meanMs}</td>
                  <td className="mono">{row.maxMs}</td>
                </tr>
              ))}
              {(metrics?.latency ?? []).map((row: any, i: number) => (
                <tr key={`t${i}`}>
                  <td className="mono">端到端</td>
                  <td className="mono">{row.count}</td>
                  <td className="mono">{row.meanMs}</td>
                  <td className="mono">{row.maxMs}</td>
                </tr>
              ))}
              {(metrics?.phaseLatency ?? []).length === 0 && (metrics?.latency ?? []).length === 0 && (
                <tr><td colSpan={4} className="empty">暂无耗时样本</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <div className="panel">
          <h3>网关耗时（按用途）</h3>
          {(metrics?.gatewayLatency ?? []).length === 0 ? (
            <div className="empty">暂无网关调用</div>
          ) : (
            <table>
              <thead>
                <tr><th>用途</th><th>次数</th><th>均值 ms</th><th>最大 ms</th></tr>
              </thead>
              <tbody>
                {(metrics?.gatewayLatency ?? []).map((row: any, i: number) => (
                  <tr key={i}>
                    <td className="mono" title={row.key}>{row.key ? gatewayLabel(row.key) : '—'}</td>
                    <td className="mono">{row.count}</td>
                    <td className="mono">{row.meanMs}</td>
                    <td className="mono">{row.maxMs}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <h3 style={{ marginTop: 16 }}>模型流式三段</h3>
          {(metrics?.gatewayFirstFrame ?? []).length === 0
            && (metrics?.gatewayFirstToken ?? []).length === 0 ? (
            <div className="empty">尚无流式仲裁样本</div>
          ) : (
            <table>
              <thead>
                <tr><th>段</th><th>用途</th><th>次数</th><th>均值 ms</th><th>最大 ms</th></tr>
              </thead>
              <tbody>
                {streamRows(metrics).map((row) => (
                  <tr key={row.segment + row.key}>
                    <td>{row.segment}</td>
                    <td className="mono" title={row.key}>{gatewayLabel(row.key)}</td>
                    <td className="mono">{row.count}</td>
                    <td className="mono">{row.meanMs}</td>
                    <td className="mono">{row.maxMs}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div style={{ marginTop: 12, fontSize: 12, color: 'var(--muted)' }}>
            往返 mean={metrics?.roundTrips?.mean ?? '—'}
            · max={metrics?.roundTrips?.max ?? '—'}
            · prompt 字符 mean={metrics?.promptChars?.mean ?? '—'}
          </div>
        </div>
        <div className="panel">
          <h3>仲裁 vs 召回</h3>
          <Bars data={metrics?.arbitrationVsRecall} />
          <h3 style={{ marginTop: 16 }}>模板渲染</h3>
          <Bars data={metrics?.templates} />
          <h3 style={{ marginTop: 16 }}>任务迁移</h3>
          <Bars data={metrics?.taskTransitions} />
        </div>
      </div>

      <div className="panel" style={{ marginBottom: 16 }}>
        <h3>最近会话</h3>
        <p className="recent-description">
          内存环形缓冲（约 100 条，重启即空）。点开会话看轮次，再点轮次看 Observation。
        </p>
        {recent.length === 0 ? (
          <div className="empty">还没有请求。去对话页发一句。</div>
        ) : (
          <div className="recent-table-scroll">
            <table className="recent-table">
              <thead>
                <tr>
                  <th></th>
                  <th>最近时间</th><th>session</th><th>轮次</th><th>最近用户原话</th>
                  <th>最近出口</th><th>Runtime</th><th>当前能力</th><th>累计耗时</th>
                </tr>
              </thead>
              <tbody>
                {recentSessions.map((session) => {
                  const open = expandedSessions.has(session.sessionId)
                  const latest = session.turns[session.turns.length - 1]
                  const totalElapsed = session.turns.reduce((sum, turn) => sum + turn.elapsedMillis, 0)
                  return (
                    <Fragment key={session.sessionId}>
                      <tr className="recent-session-row" onClick={() => toggleSession(session.sessionId)}>
                        <td>
                          <button className="disclosure" type="button">{open ? '▾' : '▸'}</button>
                        </td>
                        <td className="mono" title={String(latest.at)}>
                          {formatOperationsTime(latest.at)}
                        </td>
                        <td className="mono" title={session.sessionId}>{shortId(session.sessionId)}</td>
                        <td className="mono">{session.turns.length}</td>
                        <td className="recent-query">{latest.query}</td>
                        <td>
                          <span className={`tag ${latest.decision}`} title={latest.decision}>
                            {decisionLabel(latest.decision)}
                          </span>
                        </td>
                        <td>
                          <span className={`tag ${runtimeTone(latest.loopExecution?.status ?? latest.planExecution?.state)}`}>
                            {runtimeStatusLabel(latest.loopExecution?.status ?? latest.planExecution?.state)}
                          </span>
                        </td>
                        <td className="mono">{latest.capabilityId ?? '—'}</td>
                        <td className="mono">{totalElapsed}ms</td>
                      </tr>
                      {open && (
                        <tr className="recent-session-detail">
                          <td colSpan={9}>
                            <div className="recent-turns">
                              <table>
                                <thead>
                                  <tr>
                                    <th></th><th>时间</th><th>trace</th><th>用户原话</th><th>出口</th>
                                    <th>原因码</th><th>路径</th><th>能力</th><th>置信</th><th>耗时</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {session.turns.map((turn, index) => {
                                    const turnKey = turn.traceId + turn.at
                                    const turnOpen = expandedTurn === turnKey
                                    return (
                                      <Fragment key={turnKey}>
                                        <tr
                                          className="recent-turn-row"
                                          onClick={() => setExpandedTurn(turnOpen ? null : turnKey)}
                                        >
                                          <td>
                                            <button className="disclosure" type="button">
                                              {turnOpen ? '▾' : '▸'}
                                            </button>
                                          </td>
                                          <td className="mono" title={String(turn.at)}>
                                            {formatOperationsTime(turn.at)}
                                          </td>
                                          <td className="mono" title={turn.traceId}>{shortId(turn.traceId)}</td>
                                          <td className="recent-query">
                                            <span className="turn-number">{index + 1}</span>{turn.query}
                                          </td>
                                          <td>
                                            <span className={`tag ${turn.decision}`} title={turn.decision}>
                                              {decisionLabel(turn.decision)}
                                            </span>
                                          </td>
                                          <td className="mono" title={turn.reasonCode ?? undefined}>
                                            {reasonLabel(turn.reasonCode)}
                                          </td>
                                          <td className="mono" title={turn.path?.exitPath ?? turn.shortCircuit ?? undefined}>
                                            {exitPathLabel(turn.path?.exitPath ?? turn.shortCircuit)}
                                          </td>
                                          <td className="mono">{turn.capabilityId ?? '—'}</td>
                                          <td className="mono">
                                            {turn.confidence == null ? '—' : turn.confidence.toFixed(2)}
                                          </td>
                                          <td className="mono">{turn.elapsedMillis}ms</td>
                                        </tr>
                                        {turnOpen && (
                                          <tr className="recent-turn-detail">
                                            <td colSpan={10}>
                                              <PathFlowDetail
                                                path={turn.path}
                                                capabilityId={turn.capabilityId}
                                                gatewayCalls={turn.gatewayCalls}
                                                moduleSteps={turn.moduleSteps}
                                                collaboration={turn.collaboration}
                                                planExecution={turn.planExecution}
                                                loopExecution={turn.loopExecution}
                                                traceId={turn.traceId}
                                                variant="summary"
                                                emptyHint="此条无路径摘要。再发一句新请求即可。"
                                              />
                                            </td>
                                          </tr>
                                        )}
                                      </Fragment>
                                    )
                                  })}
                                </tbody>
                              </table>
                            </div>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="panel" style={{ marginBottom: 16 }}>
        <h3>智能体清单</h3>
        <table>
          <thead>
            <tr><th>智能体</th><th>实现状态</th><th>运行状态</th><th>实例</th><th>能力 / 领域</th></tr>
          </thead>
          <tbody>
            {(agents?.agents ?? []).map((a) => (
              <tr key={a.agentId}>
                <td>
                  <div>{a.displayName}</div>
                  <div className="mono" style={{ color: 'var(--muted)' }}>{a.agentId}</div>
                </td>
                <td>
                  <span className={`tag ${a.implementationStatus === 'IMPLEMENTED' ? 'ok' : 'muted'}`}>
                    {a.implementationStatus === 'IMPLEMENTED' ? '已实现' : '未实现'}
                  </span>
                </td>
                <td>
                  <span className={`tag ${a.runtimeStatus === 'ONLINE' ? 'ok' : a.runtimeStatus === 'UNHEALTHY' ? 'danger' : 'muted'}`}>
                    {a.runtimeStatus === 'ONLINE' ? '在线' : a.runtimeStatus === 'UNHEALTHY' ? '不健康' : '离线'}
                  </span>
                </td>
                <td className="mono">{a.instances}</td>
                <td className="mono">
                  {a.capabilities.length > 0 ? a.capabilities.join('、') : a.domains.join('、') || '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {(agents?.agents ?? []).length === 0 && <div className="empty">没有发现 Agent 定义。</div>}
        {agents?.registry?.enabled === false && (
          <div className="empty">Nacos 服务发现未启用；“已配置”表示目录与资产存在，不代表实例在线。</div>
        )}
      </div>

      <CacheBrowserPanel
        cache={cache}
        control={decisionCacheControl}
        kind={cacheKind}
        onKindChange={setCacheKind}
        expandedKey={expandedCacheKey}
        onExpand={setExpandedCacheKey}
        writeEnabled={overview?.writeEnabled ?? false}
        onToggle={async (enabled) => {
          const next = await api.setDecisionCacheEnabled(enabled)
          setDecisionCacheControl(next)
          setCache(await api.cache(cacheKind || undefined))
        }}
        onChanged={() => api.cache(cacheKind || undefined).then(setCache).catch(() => {})}
      />
    </>
  )
}

function CacheBrowserPanel({
  cache,
  control,
  kind,
  onKindChange,
  expandedKey,
  onExpand,
  writeEnabled,
  onToggle,
  onChanged,
}: {
  cache: CacheBrowser | null
  control: DecisionCacheControl | null
  kind: string
  onKindChange: (kind: string) => void
  expandedKey: string | null
  onExpand: (key: string | null) => void
  writeEnabled: boolean
  onToggle: (enabled: boolean) => Promise<void>
  onChanged: () => void
}) {
  const [changing, setChanging] = useState(false)

  async function toggle(enabled: boolean) {
    if (!writeEnabled || control?.available !== true || changing) return
    setChanging(true)
    try {
      await onToggle(enabled)
    } catch (e) {
      alert(String(e))
    } finally {
      setChanging(false)
    }
  }

  async function remove(entry: CacheEntry) {
    if (!writeEnabled) return
    if (!confirm(`删除缓存键？\n${entry.key}`)) return
    try {
      await api.deleteCache(entry.key)
      onChanged()
    } catch (e) {
      alert(String(e))
    }
  }

  return (
    <div className="panel">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <h3 style={{ marginRight: 'auto' }}>Redis 缓存（全量浏览）</h3>
        <label style={{ fontSize: 13, color: 'var(--muted)' }}>
          <input
            type="checkbox"
            checked={control?.enabled ?? false}
            disabled={!writeEnabled || control?.available !== true || changing}
            onChange={(event) => void toggle(event.target.checked)}
          />
          {' '}问法决策缓存 {control?.enabled ? '已开启' : '已关闭'}
        </label>
      </div>
      <p className="recent-description">
        看「哪些问法被缓存」：类型选「出口决策」。列表「问法」列即归一化 query；
        展开后上面是问法与上下文，下面是缓存的 RouteDecision。关闭只影响出口决策，
        不会删除会话轮次、上下文、任务或 Loop 记录。
      </p>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
        <label style={{ fontSize: 13, color: 'var(--muted)' }}>
          类型{' '}
          <select value={kind} onChange={(e) => onKindChange(e.target.value)}>
            <option value="">全部</option>
            {(cache?.kinds ?? [
              { id: 'decision', label: '出口决策' },
              { id: 'turns', label: '会话轮次投影' },
              { id: 'affinity', label: '会话亲和' },
              { id: 'lock', label: '会话锁' },
              { id: 'other', label: '其它平台键' },
            ]).map((k) => (
              <option key={k.id} value={k.id}>{k.label}</option>
            ))}
          </select>
        </label>
        <button className="ghost" type="button" onClick={onChanged}>刷新</button>
        {cache?.counts && (
          <span className="mono" style={{ fontSize: 12, color: 'var(--muted)' }}>
            {Object.entries(cache.counts).map(([k, v]) => `${k}:${v}`).join(' · ')}
            {cache.truncated ? ' · 已截断' : ''}
          </span>
        )}
      </div>
      {cache == null ? (
        <div className="empty">加载中…</div>
      ) : !cache.available ? (
        <div className="empty">{cache.message ?? 'Redis 不可用'}</div>
      ) : cache.entries.length === 0 ? (
        <div className="empty">
          {kind === 'decision' && control?.enabled === false
            ? '问法决策缓存已关闭；入口每次重新判定，不读取或写入出口缓存。'
            : '当前没有命中的缓存键。'}
        </div>
      ) : (
        <div className="recent-table-scroll">
          <table className="recent-table">
            <thead>
              <tr>
                <th></th>
                <th>类型</th>
                <th>问法 / 标识</th>
                <th>缓存结论</th>
                <th>TTL</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {cache.entries.map((entry) => {
                const open = expandedKey === entry.key
                return (
                  <Fragment key={entry.key}>
                    <tr className="recent-session-row" onClick={() => onExpand(open ? null : entry.key)}>
                      <td>
                        <button className="disclosure" type="button">{open ? '▾' : '▸'}</button>
                      </td>
                      <td><span className="tag muted">{entry.kindLabel}</span></td>
                      <td className="recent-query" title={entry.key}>
                        {entry.kind === 'decision'
                          ? (entry.query || (entry.queryMissing ? '（旧键无问法，再发一句可补）' : '—'))
                          : shortId(entry.key)}
                      </td>
                      <td className="mono">{cacheDecisionSummary(entry)}</td>
                      <td className="mono">{entry.ttlLabel ?? '—'}</td>
                      <td>
                        {writeEnabled && (
                          <button
                            className="ghost"
                            type="button"
                            onClick={(e) => { e.stopPropagation(); void remove(entry) }}
                          >
                            删除
                          </button>
                        )}
                      </td>
                    </tr>
                    {open && (
                      <tr className="recent-session-detail">
                        <td colSpan={6}>
                          {entry.kind === 'decision' && (
                            <div style={{ marginBottom: 12, fontSize: 13 }}>
                              <div><b>问法</b> {entry.query || '—'}</div>
                              {entry.rawQuery && entry.rawQuery !== entry.query && (
                                <div style={{ color: 'var(--muted)' }}>原文 {entry.rawQuery}</div>
                              )}
                              <div className="mono" style={{ color: 'var(--muted)', fontSize: 12, marginTop: 4 }}>
                                {[entry.spaceId, entry.channel, entry.page].filter(Boolean).join(' · ') || '—'}
                              </div>
                            </div>
                          )}
                          <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>缓存内容</div>
                          <pre className="mono" style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>
                            {entry.valuePreview
                              ?? (entry.value != null ? JSON.stringify(entry.value, null, 2) : entry.error ?? '—')}
                          </pre>
                          <div className="mono" style={{ marginTop: 8, color: 'var(--muted)', fontSize: 11 }}>
                            {entry.key}
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function cacheDecisionSummary(entry: CacheEntry): string {
  const value = entry.value
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const obj = value as Record<string, unknown>
    if (typeof obj.decision === 'string') {
      const conf = typeof obj.confidence === 'number' ? obj.confidence.toFixed(2) : '—'
      const caps = Array.isArray(obj.candidateIds)
        ? obj.candidateIds.join(',')
        : ''
      return `${obj.decision} · ${conf}${caps ? ` · ${caps}` : ''}`
    }
  }
  if (Array.isArray(value)) return `${value.length} items`
  if (typeof entry.valuePreview === 'string') {
    return entry.valuePreview.replace(/\s+/g, ' ').slice(0, 60)
  }
  return entry.bytes != null ? `${entry.bytes}B` : '—'
}

interface RecentSession {
  sessionId: string
  turns: RecentEntry[]
}

function groupRecentBySession(entries: RecentEntry[]): RecentSession[] {
  const grouped = new Map<string, RecentEntry[]>()
  entries.forEach((entry) => {
    const sessionId = entry.sessionId?.trim() || `trace:${entry.traceId}`
    const turns = grouped.get(sessionId)
    if (turns) turns.push(entry)
    else grouped.set(sessionId, [entry])
  })
  return Array.from(grouped, ([sessionId, turns]) => ({
    sessionId,
    turns: turns.sort((left, right) => operationsTimeMillis(left.at) - operationsTimeMillis(right.at)),
  }))
}

function formatInterval(milliseconds: number) {
  return milliseconds % 1000 === 0 ? `${milliseconds / 1000} 秒` : `${milliseconds} 毫秒`
}

function ModuleCard({ title, tone, lines }: {
  title: string
  tone: 'ok' | 'warn' | 'danger'
  lines: ReactNode[]
}) {
  return (
    <div className="panel" style={{ padding: 12 }}>
      <div style={{ marginBottom: 8 }}>
        <span className={`tag ${tone}`}>{title}</span>
      </div>
      <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: 'var(--muted)' }}>
        {lines.map((line, i) => (
          <li key={i}>{line}</li>
        ))}
      </ul>
    </div>
  )
}

function Stat({ label, value, danger }: {
  label: string
  value: string | number | undefined
  danger?: boolean
}) {
  return (
    <div className="panel stat">
      <span className="value" style={danger ? { color: 'var(--danger)' } : undefined}>
        {value ?? '—'}
      </span>
      <span className="label">{label}</span>
    </div>
  )
}

function Bars({ data }: { data?: Record<string, number> }) {
  const entries = Object.entries(data ?? {}).filter(([, v]) => v > 0)
  if (entries.length === 0) return <div className="empty">暂无数据</div>
  const max = Math.max(...entries.map(([, v]) => v))
  return (
    <>
      {entries.sort((a, b) => b[1] - a[1]).map(([name, count]) => (
        <div className="bar-row" key={name}>
          <span className="name" title={name}>{metricKeyLabel(name)}</span>
          <span className="track"><span className="fill" style={{ width: `${(count / max) * 100}%` }} /></span>
          <span className="count">{count}</span>
        </div>
      ))}
    </>
  )
}

function alarmLabel(key: string): string {
  const labels: Record<string, string> = {
    gatewayBudgetExceeded: '网关往返超预算（已废止）',
    contextDegraded: '上下文降级',
    sideEffectBlocked: '副作用被拦',
    answerAuditBlocked: '答案被审核拦下',
    tenantHeaderRejected: '租户头被拒',
    agentTimeout: 'Agent 超时',
    timeoutClamped: '超时被压到上限',
  }
  return labels[key] ?? key
}

function onOff(v: unknown): string {
  if (v == null) return '—'
  return v ? '开' : '关'
}

function runtimeStatusLabel(status?: string | null): string {
  return ({
    NEW: '新建', RUNNING: '执行中', WAITING_USER: '等待用户',
    WAITING_REVIEW: '等待确认', WAITING_CONFIRMATION: '等待确认',
    COMPLETED: '已完成', FAILED: '失败', HANDED_OFF: '已转人工',
    EXPIRED: '已过期', CANCELLED: '已取消',
  } as Record<string, string>)[status ?? ''] ?? status ?? '—'
}

function runtimeTone(status?: string | null): string {
  if (status === 'COMPLETED') return 'ok'
  if (['FAILED', 'HANDED_OFF', 'EXPIRED'].includes(status ?? '')) return 'warn'
  return 'muted'
}

function streamRows(metrics: Record<string, any> | null): {
  segment: string
  key: string
  count: number
  meanMs: number
  maxMs: number
}[] {
  if (!metrics) return []
  const packs: [string, any[]][] = [
    ['首帧', metrics.gatewayFirstFrame ?? []],
    ['首 token', metrics.gatewayFirstToken ?? []],
    ['均 token', metrics.gatewayAvgToken ?? []],
  ]
  return packs.flatMap(([segment, rows]) =>
    rows.map((row: any) => ({
      segment,
      key: row.key ?? '—',
      count: row.count,
      meanMs: row.meanMs,
      maxMs: row.maxMs,
    })),
  )
}
