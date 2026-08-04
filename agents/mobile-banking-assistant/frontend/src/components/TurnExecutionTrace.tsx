import type { ChatResponse, RecentEntry, RuntimeModuleStep } from '../api'

export interface EntryAgent {
  id: string
  displayName: string
}

export function TurnExecutionTrace(props: {
  response: ChatResponse
  observation?: RecentEntry | null
  entryAgent: EntryAgent
}) {
  const { observation, entryAgent } = props
  if (!observation) {
    return (
      <div className="turn-execution-trace pending" aria-label="执行轨迹同步中">
        <div className="execution-owner-row">
          <span className="execution-status pending" aria-hidden="true" />
          <span className="execution-caption">处理方</span>
          <strong>{entryAgent.displayName}</strong>
          <span className="execution-agent-id">{entryAgent.id}</span>
          <span className="execution-sync">执行轨迹同步中</span>
        </div>
      </div>
    )
  }

  const agents = agentChain(observation, entryAgent.id)
  const handlerId = agents[agents.length - 1] ?? entryAgent.id
  const delegated = handlerId !== entryAgent.id
  const warning = hasWarning(observation.moduleSteps)
  const handlerLabel = handlerId === entryAgent.id ? entryAgent.displayName : handlerId

  return (
    <div className="turn-execution-trace" aria-label="本轮执行轨迹">
      <div className="execution-owner-row">
        <span className={`execution-status ${warning ? 'warning' : 'success'}`} aria-hidden="true" />
        <span className="execution-caption">处理方</span>
        <strong>{handlerLabel}</strong>
        {handlerId !== handlerLabel && <span className="execution-agent-id">{handlerId}</span>}
        {delegated && <span className="execution-mode">Agent 委托</span>}
      </div>

      {agents.length > 1 && (
        <div className="execution-chain agent-chain" aria-label="Agent 委托链">
          {agents.map((agentId, index) => (
            <TraceNode
              key={`${agentId}-${index}`}
              label={agentId === entryAgent.id ? entryAgent.displayName : agentId}
              separator={index > 0 ? 'A2A' : undefined}
              agent
            />
          ))}
        </div>
      )}
    </div>
  )
}

function TraceNode(props: {
  label: string
  separator?: string
  warning?: boolean
  agent?: boolean
}) {
  return (
    <>
      {props.separator && (
        <span className={props.separator === 'A2A' ? 'execution-a2a' : 'execution-arrow'} aria-hidden="true">
          {props.separator === 'A2A' ? '→ A2A →' : props.separator}
        </span>
      )}
      <span className={`execution-node${props.agent ? ' agent' : ''}${props.warning ? ' warning' : ''}`}>
        {props.label}
      </span>
    </>
  )
}

export function agentChain(observation: RecentEntry, entryAgentId: string): string[] {
  const chain = [entryAgentId]
  const delegations = [...(observation.collaboration?.delegations ?? [])]
    .sort((left, right) => left.depth - right.depth)

  for (const hop of delegations) {
    appendAgent(chain, hop.sourceAgentId)
    appendAgent(chain, hop.targetAgentId)
  }

  if (delegations.length === 0) {
    for (const step of observation.moduleSteps ?? []) {
      if (step.module !== 'a2a-client') continue
      appendAgent(chain, stringValue(step.input?.sourceAgent))
      appendAgent(chain, stringValue(step.input?.targetAgent))
    }
  }
  if (chain.length === 1) {
    for (const task of observation.collaboration?.tasks ?? []) {
      appendAgent(chain, task.agentId)
    }
  }
  return chain
}

function appendAgent(chain: string[], agentId?: string) {
  if (!agentId || chain[chain.length - 1] === agentId) return
  if (!chain.includes(agentId)) chain.push(agentId)
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function hasWarning(steps?: RuntimeModuleStep[]): boolean {
  return (steps ?? []).some((step) => warningOutcome(step.outcome))
}

function warningOutcome(value: string): boolean {
  return ['ERROR', 'FAILED', 'FAILURE', 'REJECTED', 'TIMEOUT', 'PARTIAL', 'UNKNOWN_OUTCOME']
    .includes(value)
}
