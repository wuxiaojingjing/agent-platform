import { useEffect, useMemo, useState } from 'react'
import {
  api,
  PromptOptimizationMode,
  PromptOptimizationSnapshot,
  PromptOptimizationTrajectory,
} from '../api'

type PromptPane = 'current' | 'candidate'

export function PromptOptimizationPage() {
  const [snapshot, setSnapshot] = useState<PromptOptimizationSnapshot | null>(null)
  const [modeId, setModeId] = useState('context-rewrite')
  const [selectedCase, setSelectedCase] = useState<string | null>(null)
  const [pane, setPane] = useState<PromptPane>('current')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    api.promptOptimization().then(
      (next) => {
        setSnapshot(next)
        setError(null)
        setLoading(false)
      },
      (reason) => {
        setError(String(reason))
        setLoading(false)
      },
    )
  }

  useEffect(load, [])

  const mode = useMemo(
    () => snapshot?.modes.find((item) => item.id === modeId) ?? snapshot?.modes[0] ?? null,
    [snapshot, modeId],
  )
  const trajectory = mode?.trajectories.find((item) => item.caseId === selectedCase) ?? null

  const switchMode = (next: PromptOptimizationMode) => {
    setModeId(next.id)
    setSelectedCase(null)
    setPane(next.candidate.available ? 'candidate' : 'current')
  }

  if (loading && !snapshot) return <div className="empty">正在读取优化工作区…</div>

  return (
    <div className="promptopt-page">
      <div className="promptopt-toolbar">
        <div className="segmented" role="tablist" aria-label="优化目标">
          {snapshot?.modes.map((item) => (
            <button
              key={item.id}
              className={item.id === mode?.id ? 'active' : ''}
              onClick={() => switchMode(item)}
              role="tab"
              aria-selected={item.id === mode?.id}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="spacer" />
        <span className="tag muted">离线优化</span>
        <button className="ghost" onClick={load} disabled={loading}>刷新</button>
      </div>

      {error && <div className="banner danger">读取优化工作区失败：{error}</div>}
      {mode && <OptimizerMode mode={mode} snapshot={snapshot!} pane={pane} setPane={setPane}
        trajectory={trajectory} selectCase={setSelectedCase} />}
    </div>
  )
}

function OptimizerMode({
  mode,
  snapshot,
  pane,
  setPane,
  trajectory,
  selectCase,
}: {
  mode: PromptOptimizationMode
  snapshot: PromptOptimizationSnapshot
  pane: PromptPane
  setPane: (pane: PromptPane) => void
  trajectory: PromptOptimizationTrajectory | null
  selectCase: (caseId: string | null) => void
}) {
  const baseline = mode.candidate.baseline
  const trajectoryVersion = mode.trajectoryAssetVersions.join(', ') || '—'

  return (
    <>
      {mode.trajectoriesStale && (
        <div className="banner warn">
          冻结轨迹已过期：轨迹 {trajectoryVersion}，当前资产 {snapshot.assetVersion}
        </div>
      )}
      {mode.candidate.status === 'STALE_TRAJECTORIES' && (
        <div className="banner warn">
          候选报告基于旧轨迹集，当前 {mode.trajectoryCount} 条轨迹需要重新评测
        </div>
      )}

      <div className="grid cols-4 promptopt-stats">
        <StatusStat label="Prompt 版本" value={mode.promptVersion || '—'} />
        <StatusStat label="冻结轨迹" value={`${mode.trajectoryCount} 条`} />
        <StatusStat label="轨迹状态" value={mode.trajectoriesStale ? '需要重录' : '可评测'}
          tone={mode.trajectoriesStale ? 'warn' : 'ok'} />
        <StatusStat label="候选状态" value={candidateStatus(mode)}
          tone={mode.candidate.available ? 'warn' : 'muted'} />
      </div>

      {mode.candidate.available && (baseline || mode.candidate.score) && (
        <div className="promptopt-score-band">
          <Score label="基线" value={baseline || '—'} />
          <span className="score-arrow">→</span>
          <Score label="候选" value={mode.candidate.score || '—'} />
          <span className="mono candidate-time">
            {formatTime(mode.candidate.generatedAt)} · {mode.candidate.fileName}
          </span>
        </div>
      )}

      <div className="promptopt-workspace">
        <section className="panel trajectory-panel">
          <div className="section-heading">
            <h3>冻结轨迹</h3>
            <span className="mono">{mode.trajectoryFile}</span>
          </div>
          {mode.trajectories.length === 0 ? (
            <div className="empty">没有冻结轨迹</div>
          ) : (
            <div className="trajectory-list">
              <table>
                <thead><tr><th>用例</th><th>用户输入</th><th>历史</th><th>真值</th></tr></thead>
                <tbody>
                  {mode.trajectories.map((item) => (
                    <tr key={item.caseId} className={trajectory?.caseId === item.caseId ? 'selected' : ''}
                      onClick={() => selectCase(trajectory?.caseId === item.caseId ? null : item.caseId)}>
                      <td className="mono">{item.caseId}</td>
                      <td>{item.query}</td>
                      <td>{item.conversationHistory?.length ?? 0} 条消息</td>
                      <td className="trajectory-truth">{compactJson(item.truth)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {trajectory && (
            <div className="trajectory-detail">
              <div className="section-heading">
                <h3>对话历史</h3>
                <span className="mono">{trajectory.conversationHistory?.length ?? 0} 条消息</span>
              </div>
              {(trajectory.conversationHistory?.length ?? 0) === 0 ? (
                <div className="empty history-empty">该用例没有历史消息</div>
              ) : (
                <div className="history-messages">
                  {trajectory.conversationHistory.map((message, index) => (
                    <div className={`history-message role-${message.role ?? 'unknown'}`}
                      key={`${message.ref ?? message.role ?? 'message'}-${index}`}>
                      <div className="history-message-meta">
                        <span className="history-role">{historyRole(message.role)}</span>
                        <span className="mono">{message.ref ?? `message-${index + 1}`}</span>
                      </div>
                      <pre>{prettyJson(message.content)}</pre>
                    </div>
                  ))}
                </div>
              )}
              <div className="section-heading">
                <h3>模型冻结输入</h3>
                <span className="mono">{trajectory.caseId}</span>
              </div>
              <pre>{trajectory.modelInput}</pre>
            </div>
          )}
        </section>

        <section className="panel prompt-review-panel">
          <div className="section-heading">
            <div className="segmented compact" role="tablist" aria-label="Prompt 版本">
              <button className={pane === 'current' ? 'active' : ''} onClick={() => setPane('current')}>当前</button>
              <button className={pane === 'candidate' ? 'active' : ''}
                disabled={!mode.candidate.available} onClick={() => setPane('candidate')}>候选</button>
            </div>
            <span className="mono">
              {pane === 'candidate' ? mode.candidate.version : `${mode.promptPath} · ${mode.promptVersion}`}
            </span>
          </div>
          <textarea readOnly value={pane === 'candidate' ? mode.candidate.prompt : mode.currentPrompt} />
        </section>
      </div>
    </>
  )
}

function StatusStat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="panel stat promptopt-stat">
      <span className={`value ${tone ?? ''}`}>{value}</span>
      <span className="label">{label}</span>
    </div>
  )
}

function Score({ label, value }: { label: string; value: string }) {
  return <span><span className="score-label">{label}</span><span className="mono">{value}</span></span>
}

function compactJson(value: unknown): string {
  const text = JSON.stringify(value)
  return text.length > 110 ? `${text.slice(0, 107)}...` : text
}

function prettyJson(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

function historyRole(role?: string): string {
  if (role === 'user') return '用户'
  if (role === 'assistant') return '助手'
  if (role === 'tool') return '工具'
  return role || '未知'
}

function formatTime(value: string | null): string {
  if (!value) return '时间未知'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function candidateStatus(mode: PromptOptimizationMode): string {
  if (!mode.candidate.available) return '尚未生成'
  if (mode.candidate.status === 'STALE_TRAJECTORIES') return '需要重评'
  return '待人工审阅'
}
