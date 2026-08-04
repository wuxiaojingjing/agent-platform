import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ChatResponse, RecentEntry, RuntimeModuleStep } from '../api'
import { agentChain, TurnExecutionTrace } from './TurnExecutionTrace'

const entryAgent = { id: 'agent.mobile-banking-assistant', displayName: '手机银行助手' }

describe('TurnExecutionTrace', () => {
  it('renders the local handler without exposing the internal module chain', () => {
    const html = renderToStaticMarkup(
      <TurnExecutionTrace response={response} observation={observation([
        step('context-engine'),
        step('intent-recall'),
        step('task-orchestrator'),
        step('response-engine'),
      ])} entryAgent={entryAgent} />,
    )

    expect(html).toContain('处理方')
    expect(html).toContain('手机银行助手')
    expect(html).not.toContain('模块执行链')
    expect(html).not.toContain('混合召回')
  })

  it('renders an A2A handoff to the target Agent', () => {
    const obs = observation([step('a2a-client')])
    obs.collaboration = { delegations: [{
      delegationId: 'd1',
      sourceAgentId: entryAgent.id,
      targetAgentId: 'agent.account',
      rootTaskId: 'root', parentTaskId: null, sourceTaskId: null,
      mode: 'GOAL', capabilityId: 'agent.account', depth: 1,
      outcome: 'SUCCEEDED', reasonCode: null,
    }] }
    const html = renderToStaticMarkup(
      <TurnExecutionTrace response={response} observation={obs} entryAgent={entryAgent} />,
    )

    expect(html).toContain('agent.account')
    expect(html).toContain('Agent 委托')
    expect(html).toContain('→ A2A →')
  })

  it('preserves a multi-hop Agent chain in depth order', () => {
    const obs = observation([])
    obs.collaboration = { delegations: [
      { delegationId: 'd2', sourceAgentId: 'agent.finance', targetAgentId: 'agent.fund', rootTaskId: null, parentTaskId: null, sourceTaskId: null, mode: 'TASK', capabilityId: null, depth: 2, outcome: 'SUCCEEDED', reasonCode: null },
      { delegationId: 'd1', sourceAgentId: entryAgent.id, targetAgentId: 'agent.finance', rootTaskId: null, parentTaskId: null, sourceTaskId: null, mode: 'GOAL', capabilityId: null, depth: 1, outcome: 'SUCCEEDED', reasonCode: null },
    ] }

    expect(agentChain(obs, entryAgent.id)).toEqual([
      entryAgent.id, 'agent.finance', 'agent.fund',
    ])
  })

  it('renders a stable pending trace row before observation arrives', () => {
    const html = renderToStaticMarkup(
      <TurnExecutionTrace response={response} observation={null} entryAgent={entryAgent} />,
    )

    expect(html).toContain('turn-execution-trace pending')
    expect(html).toContain('执行轨迹同步中')
    expect(html).toContain('手机银行助手')
  })

})

const response: ChatResponse = {
  traceId: 'trace-1',
  text: '已完成',
  decision: {
    decision: 'EXECUTE_CAPABILITY', candidateIds: [], target: null, taskShape: null,
    confidence: 1, reasonCode: null, missingSlots: [], evidenceRefs: [],
    modelVersion: 'none', promptVersion: 'none', configVersion: 'v1', shortCircuit: 'NONE',
  },
  plan: {}, taskId: null, usedTemplate: null, fellBack: false, degradedChannels: [], actions: [],
}

function observation(moduleSteps: RuntimeModuleStep[]): RecentEntry {
  return {
    at: '2026-08-02T00:00:00+08:00', traceId: 'trace-1', sessionId: 'session-1', query: 'test',
    decision: 'EXECUTE_CAPABILITY', reasonCode: null, shortCircuit: null, capabilityId: null,
    confidence: 1, taskId: null, templateKey: null, fellBack: false, degradedChannels: [],
    elapsedMillis: 10, moduleSteps,
  }
}

function step(module: string, outcome = 'OK'): RuntimeModuleStep {
  return { module, operation: 'run', role: 'MAIN', input: {}, output: {}, outcome, durationMs: 1 }
}
