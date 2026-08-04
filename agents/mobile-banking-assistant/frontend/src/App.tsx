import { useCallback, useEffect, useState } from 'react'
import { api, ConsoleSettings, Overview, Tenant } from './api'
import { ChatPage } from './pages/ChatPage'
import { KnowledgePage } from './pages/KnowledgePage'
import { OpsPage } from './pages/OpsPage'
import { PromptOptimizationPage } from './pages/PromptOptimizationPage'

type Tab = 'chat' | 'knowledge' | 'optimizer' | 'ops'

export default function App() {
  const [tab, setTab] = useState<Tab>('chat')
  const [overview, setOverview] = useState<Overview | null>(null)
  const [settings, setSettings] = useState<ConsoleSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tenant, setTenant] = useState<Tenant | null>(null)

  const refresh = useCallback(() => {
    api.overview().then(
      (o) => { setOverview(o); setError(null) },
      (e) => setError(String(e)),
    )
  }, [])

  useEffect(() => {
    Promise.all([api.settings(), api.overview()]).then(
      ([nextSettings, nextOverview]) => {
        setSettings(nextSettings)
        setOverview(nextOverview)
        document.title = `${nextSettings.agent.displayName} · 运营控制台`
        setTenant({
          userId: nextSettings.tenantDefaults.userId,
          spaceId: nextSettings.tenantDefaults.spaceId,
          channel: nextSettings.tenantDefaults.channel,
        })
        setError(null)
      },
      (e) => setError(String(e)),
    )
  }, [])

  return (
    <div className="app">
      <header className="top">
        <span className="brand">{settings?.agent.displayName ?? '运营控制台'}</span>
        <nav>
          <button className={tab === 'chat' ? 'active' : ''} onClick={() => setTab('chat')}>对话</button>
          <button className={tab === 'knowledge' ? 'active' : ''} onClick={() => setTab('knowledge')}>知识配置</button>
          <button className={tab === 'optimizer' ? 'active' : ''} onClick={() => setTab('optimizer')}>提示词优化</button>
          <button className={tab === 'ops' ? 'active' : ''} onClick={() => setTab('ops')}>运营观测</button>
        </nav>
        <div className="spacer" />
        {tab === 'chat' && settings && tenant && (
          <div className="runtime-controls">
            <span className="agent-id" title="当前 Agent ID">{settings.agent.id}</span>
            <label>用户 <input value={tenant.userId} onChange={(e) => setTenant({ ...tenant, userId: e.target.value })} /></label>
            <label>空间 <input value={tenant.spaceId} onChange={(e) => setTenant({ ...tenant, spaceId: e.target.value })} /></label>
            <label>渠道
              <select value={tenant.channel} onChange={(e) => setTenant({ ...tenant, channel: e.target.value })}>
                {settings.tenantDefaults.channels.map((channel) => <option key={channel} value={channel}>{channel}</option>)}
              </select>
            </label>
          </div>
        )}
        <span className="version">资产 {overview?.assetVersion ?? '—'}</span>
      </header>

      <main>
        {error && (
          <div className="banner danger">
            连不上后端：{error}。确认应用已在 localhost:8080 起来，且 huawei.finance.mobile-banking.console.enabled 未关。
          </div>
        )}
        {tab === 'chat' && settings && tenant && (
          <ChatPage tenant={tenant} settings={settings.chat} agent={settings.agent} />
        )}
        {tab === 'chat' && (!settings || !tenant) && !error && <div className="empty">正在读取运行参数…</div>}
        {tab === 'knowledge' && <KnowledgePage overview={overview} refresh={refresh} />}
        {tab === 'optimizer' && <PromptOptimizationPage />}
        {tab === 'ops' && settings && <OpsPage overview={overview} operations={settings.operations} />}
        {tab === 'ops' && !settings && !error && <div className="empty">正在读取运行参数…</div>}
      </main>
    </div>
  )
}
