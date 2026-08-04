import { useEffect, useState } from 'react'
import { api, RenderMode, ResponsePolicy, ResponsePolicyRule } from '../api'

const modes: RenderMode[] = ['TEMPLATE', 'MODEL_SELECT', 'POLISH', 'GENERATE']
const phases = ['*', 'ACK', 'PROGRESS', 'CLARIFY', 'REVIEW', 'SWITCH_REVIEW', 'CONFIRM', 'FINAL', 'ERROR']

export function ResponsePolicyPanel({ writable, onSaved }: { writable: boolean; onSaved: () => void }) {
  const [policy, setPolicy] = useState<ResponsePolicy | null>(null)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.responsePolicy().then(setPolicy).catch((error) => setMessage(String(error)))
  }, [])

  if (!policy) return <div className="response-policy-panel">{message || '正在读取回复策略…'}</div>

  const updateDefault = (patch: Partial<ResponsePolicyRule>) =>
    setPolicy({ ...policy, defaults: { ...policy.defaults, ...patch } })
  const updateRule = (index: number, patch: Partial<ResponsePolicyRule>) => {
    const rules = policy.rules.map((rule, current) => current === index ? { ...rule, ...patch } : rule)
    setPolicy({ ...policy, rules })
  }

  async function save() {
    setBusy(true)
    setMessage('')
    try {
      const result = await api.saveResponsePolicy(policy!)
      setMessage(`已生效：${result.policyVersion} · ${result.assetVersion}`)
      onSaved()
    } catch (error: any) {
      setMessage(error.body?.message ?? String(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="response-policy-panel" aria-label="回复策略">
      <div className="response-policy-heading">
        <div>
          <h3>回复策略</h3>
          <span className="mono">{policy.version} · {policy.promptVersion}</span>
        </div>
        <button className="action" disabled={!writable || busy} onClick={save}>保存并生效</button>
      </div>

      <div className="response-policy-defaults">
        <label>默认模式
          <ModeSelect value={policy.defaults.mode} onChange={(mode) => updateDefault({ mode })} />
        </label>
        <label>模型
          <input value={policy.defaults.model} placeholder="留空复用仲裁模型"
                 onChange={(event) => updateDefault({ model: event.target.value })} />
        </label>
        <label>温度
          <input type="number" min="0" max="2" step="0.1" value={policy.defaults.temperature}
                 onChange={(event) => updateDefault({ temperature: Number(event.target.value) })} />
        </label>
        <label>最大 token
          <input type="number" min="32" step="32" value={policy.defaults.maxTokens}
                 onChange={(event) => updateDefault({ maxTokens: Number(event.target.value) })} />
        </label>
      </div>

      <div className="response-policy-rules">
        {policy.rules.map((rule, index) => (
          <div className="response-policy-rule" key={index}>
            <input aria-label="租户" title="租户" value={rule.tenant} onChange={(e) => updateRule(index, { tenant: e.target.value })} />
            <input aria-label="Agent" title="Agent" value={rule.agent} onChange={(e) => updateRule(index, { agent: e.target.value })} />
            <input aria-label="场景" title="场景" value={rule.scene} onChange={(e) => updateRule(index, { scene: e.target.value })} />
            <select aria-label="阶段" value={rule.phase} onChange={(e) => updateRule(index, { phase: e.target.value })}>
              {phases.map((phase) => <option key={phase}>{phase}</option>)}
            </select>
            <ModeSelect value={rule.mode} onChange={(mode) => updateRule(index, { mode })} />
            <input aria-label="模型" title="模型；留空复用默认模型" value={rule.model}
                   placeholder="模型"
                   onChange={(e) => updateRule(index, { model: e.target.value })} />
            <input aria-label="模板集" title="允许模型选择的模板键，逗号分隔"
                   value={rule.templateSet.join(', ')} placeholder="模板集"
                   onChange={(e) => updateRule(index, { templateSet: commaList(e.target.value) })} />
            <input aria-label="温度" title="温度" type="number" min="0" max="2" step="0.1"
                   value={rule.temperature}
                   onChange={(e) => updateRule(index, { temperature: Number(e.target.value) })} />
            <input aria-label="最大 token" title="最大 token" type="number" min="32" step="32"
                   value={rule.maxTokens}
                   onChange={(e) => updateRule(index, { maxTokens: Number(e.target.value) })} />
            <button className="icon-button danger" title="删除规则" aria-label="删除规则"
                    onClick={() => setPolicy({ ...policy, rules: policy.rules.filter((_, current) => current !== index) })}>×</button>
          </div>
        ))}
        <button className="ghost" onClick={() => setPolicy({ ...policy, rules: [...policy.rules, emptyRule()] })}>
          + 添加范围规则
        </button>
      </div>
      {message && <div className="response-policy-message">{message}</div>}
    </section>
  )
}

function ModeSelect({ value, onChange }: { value: RenderMode; onChange: (mode: RenderMode) => void }) {
  return <select value={value} onChange={(event) => onChange(event.target.value as RenderMode)}>
    {modes.map((mode) => <option key={mode}>{mode}</option>)}
  </select>
}

function emptyRule(): ResponsePolicyRule {
  return { tenant: '*', agent: '*', scene: '*', phase: '*', mode: 'TEMPLATE', model: '',
    templateSet: [], temperature: 0, maxTokens: 256 }
}

function commaList(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean)
}
