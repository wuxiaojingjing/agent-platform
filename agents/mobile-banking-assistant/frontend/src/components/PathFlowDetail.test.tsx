import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { PathFlowDetail } from './PathFlowDetail'
import type { CollaborationHop, CollaborationTask, PathSummary } from '../api'

const path: PathSummary = {
  exitPath: 'L2_STRONG_RULE',
  arbitratedBy: 'SHORT_CIRCUIT',
  phaseMs: { rewrite: 3 },
  topCandidates: [],
  selectedRank: null,
  overruledTop1: null,
  runnerUpId: null,
  missingSlots: [],
  eventType: 'NEW_TASK',
}

describe('PathFlowDetail', () => {
  it('accepts sparse persisted observations without crashing', () => {
    expect(() => renderToStaticMarkup(
      <PathFlowDetail path={path} collaboration={{}} planExecution={{}} />,
    )).not.toThrow()
  })

  it('renders the complete two-hop collaboration and three target tasks', () => {
    const delegations: CollaborationHop[] = [
      hop('d1', 'agent.mobile-banking-assistant', 'agent.finance_assistant', 'GOAL', 1),
      hop('d2', 'agent.finance_assistant', 'agent.fund_service', 'TASK', 2),
    ]
    const tasks: CollaborationTask[] = [
      task('t1', 'agent.mobile-banking-assistant', 'LOCAL'),
      task('t2', 'agent.finance_assistant', 'A2A'),
      task('t3', 'agent.fund_service', 'A2A'),
    ]

    const html = renderToStaticMarkup(
      <PathFlowDetail path={path} collaboration={{ delegations, tasks }} />,
    )

    expect(html).toContain('GOAL · depth 1')
    expect(html).toContain('TASK · depth 2')
    expect(html).toContain('各层任务（3）')
    expect(html).toContain('agent.mobile-banking-assistant')
    expect(html).toContain('agent.finance_assistant')
    expect(html).toContain('agent.fund_service')
  })

  it('renders Slow Path rule strength, scores, candidates and evidence', () => {
    const html = renderToStaticMarkup(
      <PathFlowDetail
        path={path}
        planExecution={{
          source: 'RULE',
          items: [{
            order: 0,
            text: '查一下余额',
            summary: '查询账户余额',
            capabilityId: 'cap.account.balance.query',
            relation: 'PARALLEL',
            resolution: {
              strength: 'LOCKED',
              topScore: 1,
              margin: 0.75,
              candidateIds: ['cap.account.balance.query', 'cap.wealth.holding.query'],
              evidenceRefs: ['utterance:查一下余额'],
            },
          }],
        }}
      />,
    )

    expect(html).toContain('LOCKED')
    expect(html).toContain('Top1 1.000 · 分差 0.750')
    expect(html).toContain('候选 cap.account.balance.query · cap.wealth.holding.query')
    expect(html).toContain('证据 utterance:查一下余额')
  })

  it('renders structured context arrays as JSON instead of object coercion', () => {
    const html = renderToStaticMarkup(
      <PathFlowDetail
        path={path}
        moduleSteps={[{
          module: 'context-engine',
          operation: 'contextual-rewrite',
          role: 'CONTEXT',
          input: {
            inputItemCount: 2,
            availableContext: [
              { ref: 'fact:accounts', kind: 'TOOL_FACT', value: { cards: [{ index: 1 }] } },
              { ref: 'turn:session#0:utterance', kind: 'USER_TURN', value: { text: '查余额' } },
            ],
            conversationHistory: [
              { role: 'assistant', type: 'TEXT', text: '账户余额如下' },
            ],
            availableContextRefs: ['fact:accounts', 'turn:session#0:utterance'],
          },
          output: {},
          outcome: 'OK',
          durationMs: 12,
        }]}
      />,
    )

    expect(html).not.toContain('[object Object]')
    expect(html).toContain('&quot;ref&quot;:&quot;fact:accounts&quot;')
    expect(html).toContain('&quot;cards&quot;:[{&quot;index&quot;:1}]')
    expect(html).toContain('&quot;text&quot;:&quot;账户余额如下&quot;')
    expect(html).toContain('availableContextRefs=[fact:accounts, turn:session#0:utterance]')
  })
})

function hop(
  delegationId: string,
  sourceAgentId: string,
  targetAgentId: string,
  mode: 'GOAL' | 'TASK',
  depth: number,
): CollaborationHop {
  return {
    delegationId,
    sourceAgentId,
    targetAgentId,
    rootTaskId: 'root',
    parentTaskId: 'parent',
    sourceTaskId: 'source',
    mode,
    capabilityId: mode === 'GOAL' ? 'agent.finance_assistant' : 'cap.fund.product.query',
    depth,
    outcome: 'SUCCEEDED',
    reasonCode: null,
  }
}

function task(taskId: string, agentId: string, invocationOrigin: string): CollaborationTask {
  return {
    taskId,
    agentId,
    capabilityId: agentId === 'agent.mobile-banking-assistant'
      ? 'agent.finance_assistant' : 'cap.fund.product.query',
    state: 'SUCCEEDED',
    intentPath: 'FAST_PATH',
    invocationOrigin,
    guardrailStatus: 'PASSED',
    failureClass: 'NONE',
  }
}
