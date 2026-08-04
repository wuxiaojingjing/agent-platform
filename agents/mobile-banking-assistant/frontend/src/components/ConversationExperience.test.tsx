import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import type { ChatResponse, RecentEntry } from '../api'
import { clientActionOutcome, ConversationExperience } from './ConversationExperience'

describe('ConversationExperience', () => {
  it('renders slot options as direct choices', () => {
    const html = render(response({
      responsePhase: 'CLARIFY', templateKey: 'tpl.clarify.slot',
      cardComponents: ['CHOICE_LIST'], slots: { options: ['信用卡', '借记卡'] },
    }))

    expect(html).toContain('aria-label="可选项"')
    expect(html).toContain('信用卡')
    expect(html).toContain('借记卡')
  })

  it('renders knowledge-backed menu items as banking navigation choices', () => {
    const html = render(response({
      responsePhase: 'FINAL', templateKey: 'tpl.answer.standard',
      cardComponents: ['MENU_LIST'], slots: {
        menuItems: [
          { label: '信用报告', menuId: 'menu.personal_info.信用报告', query: '打开信用报告' },
          { label: '存款', menuId: 'menu.branch_service.预约存款', query: '打开网点预约存款' },
        ],
      },
    }))

    expect(html).toContain('相关菜单')
    expect(html).toContain('信用报告')
    expect(html).toContain('存款')
    expect(html.match(/choice-option/g)?.length).toBe(2)
  })

  it('renders blocked knowledge guidance as related knowledge and explicit execution choices', () => {
    const html = render(response({
      responsePhase: 'CLARIFY', templateKey: 'tpl.clarify.slot',
      cardComponents: ['CHOICE_LIST'], slots: {
        question: '目前无法根据已复核知识确认是否支持原号换卡。',
        options: ['了解换卡无忧', '了解换卡换号影响', '办理普通换卡'],
      },
    }))

    expect(html).toContain('了解换卡无忧')
    expect(html).toContain('了解换卡换号影响')
    expect(html).toContain('办理普通换卡')
    expect(html.match(/choice-option/g)?.length).toBe(3)
  })

  it('renders cross-type product comparison as an explicit two-choice clarification', () => {
    const html = render(response({
      responsePhase: 'CLARIFY', templateKey: 'tpl.product.compare.incomparable',
      cardComponents: ['CHOICE_LIST'], slots: {
        leftName: '产品A', leftType: '保险产品', rightName: '产品B', rightType: '理财产品',
        options: ['查看产品A', '查看产品B'],
      },
    }))

    expect(html).toContain('aria-label="可选项"')
    expect(html).toContain('查看产品A')
    expect(html).toContain('查看产品B')
    expect(html.match(/choice-option/g)?.length).toBe(2)
  })

  it('renders suspended tasks as structured choices', () => {
    const value = response({
      responsePhase: 'CLARIFY', templateKey: 'tpl.resume.select',
      cardComponents: ['CHOICE_LIST'], slots: { tasks: ['继续换卡', '继续转账'] },
    })
    value.actions = [
      { event: 'RESUME_SUSPENDED', label: '继续换卡', ref: 'task-card', version: 2, style: 'SECONDARY' },
      { event: 'RESUME_SUSPENDED', label: '继续转账', ref: 'task-transfer', version: 3, style: 'SECONDARY' },
    ]

    const html = render(value)

    expect(html).toContain('继续换卡')
    expect(html).toContain('继续转账')
    expect(html.match(/choice-option/g)?.length).toBe(2)
  })

  it('renders review facts, risk notice and versioned actions', () => {
    const value = response({
      responsePhase: 'CONFIRM', templateKey: 'tpl.transfer.confirm',
      cardComponents: ['REVIEW_SUMMARY', 'RISK_NOTICE'],
      riskNoticeCodes: ['FUND_MOVEMENT'],
      slots: { payee: '张三', amount: '1000', currency: '¥' },
    })
    value.actions = [
      { event: 'CONFIRM', label: '确认执行', ref: 'task-1', version: 4, style: 'PRIMARY' },
      { event: 'CANCEL', label: '取消', ref: 'task-1', version: 4, style: 'DANGER' },
    ]

    const html = render(value)

    expect(html).toContain('执行确认')
    expect(html).toContain('收款人')
    expect(html).toContain('张三')
    expect(html).toContain('¥1000')
    expect(html).toContain('涉及资金或账户变更')
    expect(html).toContain('response-action primary')
    expect(html).toContain('response-action danger')
  })

  it('renders static-plan todo progress with completed and active items', () => {
    const html = render(response({
      responsePhase: 'FINAL', templateKey: 'tpl.plan.progress',
      cardComponents: ['TASK_PROGRESS'],
      slots: {
        completedSummaries: ['查询账户余额'],
        remainingSummaries: ['查询基金产品'],
      },
    }))

    expect(html).toContain('办理进度')
    expect(html).toContain('1/2')
    expect(html).toContain('查询账户余额')
    expect(html).toContain('已完成')
    expect(html).toContain('查询基金产品')
    expect(html).toContain('处理中')
  })

  it('treats authoritative SUCCESS plan steps as completed', () => {
    const observation = recent()
    observation.planExecution = {
      planId: 'plan-1', cursor: 1, stepCount: 2, state: 'WAITING_USER',
      items: [
        { capabilityId: 'cap.account.balance.query', summary: '查询账户余额' },
        { capabilityId: 'cap.transfer', summary: '转账' },
      ],
      steps: [{
        stepIndex: 0, capabilityId: 'cap.account.balance.query', taskId: 'task-1',
        status: 'SUCCESS', failureClass: 'NONE', reasonCode: null, factKeys: [],
      }],
    }
    const html = render(response({
      responsePhase: 'CLARIFY', templateKey: 'tpl.clarify.condition',
      cardComponents: ['TASK_PROGRESS'], slots: { options: ['继续办理', '不办理'] },
    }), observation)

    expect(html).toContain('1/2')
    expect(html).toContain('查询账户余额')
    expect(html).toContain('已完成')
    expect(html).toContain('转账')
    expect(html).toContain('处理中')
  })

  it('renders loop progress from the loop authority projection', () => {
    const value = response({
      responsePhase: 'FINAL', templateKey: 'tpl.loop.final',
      cardComponents: ['LOOP_STATUS'], slots: { summary: '完成', reason: '' },
    })
    const observation = recent()
    observation.loopExecution = {
      loopId: 'loop-1', status: 'COMPLETED', iteration: 2, maxIterations: 6,
      stateVersion: 4, candidateIds: [], steps: [
        { stepIndex: 0, actionType: 'CALL_CAPABILITY', status: 'SUCCESS', retryable: false } as any,
        { stepIndex: 1, actionType: 'FINISH', status: 'SUCCESS', retryable: false } as any,
      ],
    }

    const html = render(value, observation)

    expect(html).toContain('排查进度')
    expect(html).toContain('2/6')
    expect(html).toContain('检查业务状态')
    expect(html).toContain('整理排查结果')
  })

  it('renders audited result fields without exposing unknown slots', () => {
    const html = render(response({
      responsePhase: 'FINAL', templateKey: 'tpl.balance.result',
      cardComponents: ['RESULT_SUMMARY'],
      slots: { accountAlias: '工资卡', availableBalance: '12845.60', currency: '¥', internalRef: 'secret' },
    }))

    expect(html).toContain('办理结果')
    expect(html).toContain('工资卡')
    expect(html).toContain('¥12845.60')
    expect(html).not.toContain('internalRef')
    expect(html).not.toContain('secret')
  })

  it('renders completed Static Plan facts as reusable result cards', () => {
    const html = render(response({
      responsePhase: 'CLARIFY', templateKey: 'tpl.clarify.condition',
      cardComponents: ['TASK_PROGRESS', 'RESULT_SUMMARY'],
      slots: {
        condition: '不足就别转', options: ['继续办理', '不办理'],
        resultCards: [{
          capabilityId: 'cap.account.balance.query', title: '查询账户余额',
          fields: { accountAlias: '工资卡', availableBalance: '12845.60', currency: '¥' },
        }],
      },
    }))

    expect(html).toContain('查询账户余额')
    expect(html).toContain('工资卡')
    expect(html).toContain('¥12845.60')
    expect(html).toContain('继续办理')
  })

  it('renders two same-type product observations as a comparison matrix', () => {
    const html = render(response({
      responsePhase: 'FINAL', templateKey: 'tpl.loop.final',
      cardComponents: ['LOOP_STATUS', 'RESULT_SUMMARY', 'PRODUCT_COMPARISON'],
      slots: {
        comparisonReady: true,
        resultCards: [
          { capabilityId: 'cap.wealth-product.product.query', title: '产品B · 理财',
            fields: { name: '产品B', domain: '理财', riskLevel: 'R2', returnRate: '2.6%', term: '180天' } },
          { capabilityId: 'cap.wealth-product.product-b2.query', title: '产品B2 · 理财',
            fields: { name: '产品B2', domain: '理财', riskLevel: 'R3', returnRate: '3.2%', term: '一年' } },
        ],
      },
    }))

    expect(html).toContain('aria-label="产品对比"')
    expect(html).toContain('产品B · 理财')
    expect(html).toContain('产品B2 · 理财')
    expect(html).toContain('风险等级')
    expect(html).toContain('3.2%')
    expect(html).toContain('180天')
  })

  it('keeps user-visible cards in the exact server projection order', () => {
    const html = render(response({
      responsePhase: 'CONFIRM', templateKey: 'tpl.transfer.confirm',
      cardComponents: ['TASK_PROGRESS', 'RESULT_SUMMARY', 'RISK_NOTICE', 'REVIEW_SUMMARY', 'CHOICE_LIST'],
      riskNoticeCodes: ['FUND_MOVEMENT'],
      slots: {
        completedSummaries: ['查询账户余额'], remainingSummaries: ['转账'],
        accountAlias: '工资卡', availableBalance: '12845.60', payee: '张三', amount: '2000',
        options: ['继续办理', '不办理'],
      },
    }))

    const progress = html.indexOf('data-component="TASK_PROGRESS"')
    const result = html.indexOf('data-component="RESULT_SUMMARY"')
    const risk = html.indexOf('data-component="RISK_NOTICE"')
    const review = html.indexOf('data-component="REVIEW_SUMMARY"')
    const choices = html.indexOf('data-component="CHOICE_LIST"')
    expect(progress).toBeGreaterThanOrEqual(0)
    expect(result).toBeGreaterThan(progress)
    expect(risk).toBeGreaterThan(result)
    expect(review).toBeGreaterThan(risk)
    expect(choices).toBeGreaterThan(review)
  })

  it('renders structured transaction rows and ignores malformed or internal values', () => {
    const html = render(response({
      responsePhase: 'FINAL', templateKey: 'tpl.transaction.result',
      cardComponents: ['RESULT_SUMMARY'],
      slots: {
        accountAlias: '尾号 8821 借记卡',
        transactions: [
          { date: '2026-07-24', description: '超市消费', amount: '-128.50', internalRef: 'secret-1' },
          { date: '2026-07-23', description: '工资入账', amount: '+18,600.00' },
          { date: '2026-07-22', description: '', amount: '-1.00' },
        ],
      },
    }))

    expect(html).toContain('aria-label="交易明细"')
    expect(html).toContain('最近交易')
    expect(html).toContain('2 笔')
    expect(html).toContain('2026-07-24')
    expect(html).toContain('超市消费')
    expect(html).toContain('debit')
    expect(html).toContain('工资入账')
    expect(html).toContain('credit')
    expect(html).not.toContain('secret-1')
    expect(html).not.toContain('2026-07-22')
  })

  it('renders menu action and a visible client outcome', () => {
    const value = response({
      responsePhase: 'FINAL', templateKey: 'tpl.nav.open',
      cardComponents: ['NAVIGATION'], actionCodes: ['OPEN_MENU'],
      slots: { menuName: '理财交易记录', menuId: 'menu-1', bksPath: '/wealth/trades' },
    })
    const html = render(value, undefined, clientActionOutcome('OPEN_MENU', value))

    expect(html).toContain('已找到入口')
    expect(html).toContain('理财交易记录')
    expect(html).toContain('打开“理财交易记录”')
    expect(html).toContain('已打开“理财交易记录”')
  })

  it('opens result details locally instead of sending an ambiguous chat query', () => {
    const value = response({
      responsePhase: 'ERROR', templateKey: 'tpl.fallback.uncertain',
      actionCodes: ['CHECK_DETAIL'], slots: {},
    })
    const html = render(value, undefined, clientActionOutcome('CHECK_DETAIL', value))

    expect(html).toContain('查询处理结果')
    expect(html).toContain('已打开交易明细，请核对实际处理状态。')
  })

  it('renders retry and service actions with explicit local feedback', () => {
    const retry = response({
      responsePhase: 'ERROR', templateKey: 'tpl.fallback.generic',
      actionCodes: ['RETRY'], slots: {},
    })
    const retryHtml = render(retry)
    expect(retryHtml).toContain('>重试</button>')

    const uncertain = response({
      responsePhase: 'ERROR', templateKey: 'tpl.fallback.uncertain',
      actionCodes: ['CHECK_DETAIL', 'CONTACT_SERVICE'], slots: {},
    })
    const serviceHtml = render(uncertain, undefined,
      clientActionOutcome('CONTACT_SERVICE', uncertain))
    expect(serviceHtml).toContain('查询处理结果')
    expect(serviceHtml).toContain('联系人工服务')
    expect(serviceHtml).toContain('已打开人工服务入口')
    expect(serviceHtml).not.toContain('>重试</button>')
  })

  it('replaces old buttons with a submitted state after one action is selected', () => {
    const value = response({ responsePhase: 'REVIEW', cardComponents: ['REVIEW_SUMMARY'], slots: { cardType: '信用卡' } })
    value.actions = [{ event: 'REVIEW_ACCEPT', label: '继续', ref: 'task-1', version: 2, style: 'PRIMARY' }]

    const html = render(value, undefined, undefined, '继续')

    expect(html).toContain('已选择：继续')
    expect(html).not.toContain('>继续</button>')
  })

  it('renders internal card type enum as a customer-facing label', () => {
    const html = render(response({
      responsePhase: 'REVIEW', cardComponents: ['REVIEW_SUMMARY'], slots: { cardType: 'CREDIT' },
    }))

    expect(html).toContain('信用卡')
    expect(html).not.toContain('>CREDIT<')
  })
})

function render(value: ChatResponse, observation?: RecentEntry, clientOutcome?: string, submittedLabel?: string) {
  return renderToStaticMarkup(
    <ConversationExperience
      response={value}
      observation={observation}
      busy={false}
      clientOutcome={clientOutcome}
      submittedLabel={submittedLabel}
      onAction={vi.fn()}
      onQuickReply={vi.fn()}
      onClientAction={vi.fn()}
    />,
  )
}

function response(plan: ChatResponse['plan']): ChatResponse {
  return {
    traceId: 'trace-1', text: '回复', decision: {
      decision: 'EXECUTE_CAPABILITY', candidateIds: [], target: null, taskShape: null,
      confidence: 1, reasonCode: null, missingSlots: [], evidenceRefs: [],
      modelVersion: 'none', promptVersion: 'none', configVersion: 'v1', shortCircuit: 'NONE',
    },
    plan, taskId: null, usedTemplate: null, fellBack: false, degradedChannels: [], actions: [],
  }
}

function recent(): RecentEntry {
  return {
    at: '2026-08-02T00:00:00+08:00', traceId: 'trace-1', sessionId: 'session-1', query: 'test',
    decision: 'START_LOOP', reasonCode: null, shortCircuit: null, capabilityId: null,
    confidence: 1, taskId: null, templateKey: null, fellBack: false,
    degradedChannels: [], elapsedMillis: 10,
  }
}
