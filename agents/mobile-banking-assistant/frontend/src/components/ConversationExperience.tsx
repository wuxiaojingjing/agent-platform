import type {
  ChatResponse,
  PlanExecution,
  RecentEntry,
  ResponseAction,
  ResponseComponent,
} from '../api'

interface Props {
  response: ChatResponse
  observation?: RecentEntry
  busy: boolean
  inferMissing?: boolean
  submittedLabel?: string
  clientOutcome?: string
  onAction: (action: ResponseAction) => void
  onQuickReply: (text: string) => void
  onClientAction: (code: string) => void
}

const fieldLabels: Record<string, string> = {
  accountAlias: '账户',
  availableBalance: '可用余额',
  billAmount: '账单金额',
  dueDate: '到期日',
  cardType: '卡种',
  cardTypeName: '卡种',
  cardStatus: '卡片状态',
  payrollArrived: '到账情况',
  payrollStatus: '工资状态',
  lastArrivalDate: '最近到账',
  employer: '发薪单位',
  totalAsset: '总资产',
  profit: '累计收益',
  name: '产品',
  domain: '类型',
  riskLevel: '风险等级',
  returnRate: '参考收益率',
  term: '期限',
  payee: '收款人',
  amount: '金额',
  amountBasis: '金额依据',
  serialNo: '业务编号',
  applicationNo: '申请编号',
  menuName: '目标入口',
  leftName: '产品一',
  rightName: '产品二',
  leftRiskLevel: '产品一风险',
  rightRiskLevel: '产品二风险',
  leftReturnRate: '产品一收益率',
  rightReturnRate: '产品二收益率',
}

const fieldOrder = Object.keys(fieldLabels)
const moneyFields = new Set(['availableBalance', 'billAmount', 'totalAsset', 'profit', 'amount'])

export function ConversationExperience(props: Props) {
  const { response, observation } = props
  const plan = response.plan ?? {}
  const slots = objectValue(plan.slots)
  const choices = choiceItems(response, slots)
  const menuChoices = menuItems(slots)
  const components = uniqueComponents([
    ...stringArray(plan.cardComponents),
    ...(props.inferMissing === false ? [] : inferComponents(response)),
  ])
  if (choices.length > 0 && !components.includes('CHOICE_LIST')) components.push('CHOICE_LIST')
  const serverActions = (response.actions ?? []).filter((action) => action.event !== 'RESUME_SUSPENDED')
  const clientActions = stringArray(plan.actionCodes).filter((code) =>
    ['OPEN_MENU', 'OPEN_CAPABILITY', 'CONTACT_SERVICE', 'CHECK_DETAIL', 'RETRY'].includes(code),
  )
  const progress = buildProgress(slots, observation?.planExecution)

  const visible = components.length > 0
    || serverActions.length > 0 || clientActions.length > 0 || props.clientOutcome
  if (!visible) return null

  return (
    <div className="response-experience">
      {components.map((component, index) => (
        <div
          className="response-card-stage"
          data-component={component}
          key={component}
          style={{ animationDelay: `${index * 55}ms` }}
        >
          {component === 'TASK_PROGRESS' && progress && <TaskProgress progress={progress} />}
          {component === 'LOOP_STATUS' && observation?.loopExecution && (
            <LoopProgress loop={observation.loopExecution} />
          )}
          {component === 'RESULT_SUMMARY' && <ResultCards slots={slots} />}
          {component === 'PRODUCT_COMPARISON' && <ProductComparison slots={slots} />}
          {component === 'RISK_NOTICE' && <RiskNotice codes={arrayValue(plan.riskNoticeCodes)} />}
          {component === 'REVIEW_SUMMARY' && (
            <SummaryFields title={reviewTitle(String(plan.responsePhase ?? ''))} slots={slots} />
          )}
          {component === 'NAVIGATION' && (
            <SummaryFields title="已找到入口" slots={slots} keys={['menuName']} />
          )}
          {component === 'CHOICE_LIST' && choices.length > 0 && (
            <ChoiceList
              choices={choices}
              disabled={props.busy || props.submittedLabel != null}
              onAction={props.onAction}
              onQuickReply={props.onQuickReply}
            />
          )}
          {component === 'MENU_LIST' && menuChoices.length > 0 && (
            <section aria-label="相关菜单">
              <div className="experience-heading"><strong>相关菜单</strong></div>
              <ChoiceList
                choices={menuChoices}
                disabled={props.busy || props.submittedLabel != null}
                onAction={props.onAction}
                onQuickReply={props.onQuickReply}
              />
            </section>
          )}
        </div>
      ))}
      {props.submittedLabel ? (
        <div className="action-submitted" role="status">
          <span className="action-submitted-mark" aria-hidden="true" />
          已选择：{props.submittedLabel}
        </div>
      ) : (
        <>
          {serverActions.length > 0 && (
            <div className="response-actions">
              {serverActions.map((action) => (
                <button
                  key={`${action.event}:${action.ref}:${action.version}`}
                  type="button"
                  className={`response-action ${action.style.toLowerCase()}`}
                  disabled={props.busy}
                  onClick={() => props.onAction(action)}
                >
                  {action.label}
                </button>
              ))}
            </div>
          )}
          {clientActions.length > 0 && (
            <div className="response-actions">
              {clientActions.map((code) => (
                <button
                  key={code}
                  type="button"
                  className="response-action secondary"
                  disabled={props.busy}
                  onClick={() => props.onClientAction(code)}
                >
                  {clientActionLabel(code, slots)}
                </button>
              ))}
            </div>
          )}
        </>
      )}
      {props.clientOutcome && <div className="client-action-outcome" role="status">{props.clientOutcome}</div>}
    </div>
  )
}

function uniqueComponents(values: string[]): ResponseComponent[] {
  return values.filter((value, index) => value.length > 0 && values.indexOf(value) === index)
}

function inferComponents(response: ChatResponse): ResponseComponent[] {
  const plan = response.plan ?? {}
  const phase = String(plan.responsePhase ?? '')
  const template = String(plan.templateKey ?? '')
  const slots = objectValue(plan.slots)
  const inferred: ResponseComponent[] = []
  if (phase === 'CLARIFY' && choiceStrings(slots).length > 0) inferred.push('CHOICE_LIST')
  if (arrayValue(slots.menuItems).length > 0) inferred.push('MENU_LIST')
  if (template.startsWith('tpl.plan.')) inferred.push('TASK_PROGRESS')
  if (['REVIEW', 'CONFIRM', 'SWITCH_REVIEW'].includes(phase)) inferred.push('REVIEW_SUMMARY')
  if (template.startsWith('tpl.nav.')) inferred.push('NAVIGATION')
  if (template.startsWith('tpl.loop.')) inferred.push('LOOP_STATUS')
  if (phase === 'FINAL' && template.includes('.result')) inferred.push('RESULT_SUMMARY')
  if (arrayValue(plan.riskNoticeCodes).length > 0) inferred.push('RISK_NOTICE')
  return inferred
}

interface ChoiceItem {
  key: string
  label: string
  replyText?: string
  action?: ResponseAction
}

function ChoiceList({ choices, disabled, onAction, onQuickReply }: {
  choices: ChoiceItem[]
  disabled: boolean
  onAction: (action: ResponseAction) => void
  onQuickReply: (text: string) => void
}) {
  return (
    <div className="choice-list" aria-label="可选项">
      {choices.map((choice) => (
        <button
          key={choice.key}
          type="button"
          className="choice-option"
          disabled={disabled}
          onClick={() => choice.action ? onAction(choice.action) : onQuickReply(choice.replyText ?? choice.label)}
        >
          <span>{choice.label}</span>
          <span className="choice-arrow" aria-hidden="true">›</span>
        </button>
      ))}
    </div>
  )
}

function menuItems(slots: Record<string, unknown>): ChoiceItem[] {
  return arrayValue(slots.menuItems).flatMap((value, index) => {
    const item = objectValue(value)
    const label = typeof item.label === 'string' ? item.label.trim() : ''
    const query = typeof item.query === 'string' ? item.query.trim() : ''
    const menuId = typeof item.menuId === 'string' ? item.menuId : String(index)
    return label && query ? [{ key: menuId, label, replyText: query }] : []
  })
}

function choiceItems(response: ChatResponse, slots: Record<string, unknown>): ChoiceItem[] {
  const resumeActions = (response.actions ?? []).filter((action) => action.event === 'RESUME_SUSPENDED')
  if (resumeActions.length > 0) {
    return resumeActions.map((action) => ({
      key: `${action.event}:${action.ref}:${action.version}`,
      label: action.label,
      action,
    }))
  }
  return choiceStrings(slots).map((label, index) => ({ key: `${index}:${label}`, label }))
}

function choiceStrings(slots: Record<string, unknown>): string[] {
  for (const key of ['options', 'taskSummaries', 'tasks']) {
    const values = arrayValue(slots[key]).filter((value): value is string =>
      typeof value === 'string' && value.trim().length > 0,
    )
    if (values.length > 0) return values
  }
  return []
}

interface ProgressItem {
  label: string
  state: 'DONE' | 'ACTIVE' | 'PENDING' | 'FAILED'
}

interface ProgressView {
  completed: number
  total: number
  items: ProgressItem[]
}

function buildProgress(slots: Record<string, unknown>, plan?: PlanExecution): ProgressView | null {
  const completed = arrayValue(slots.completedSummaries).filter((v): v is string => typeof v === 'string')
  const remaining = arrayValue(slots.remainingSummaries).filter((v): v is string => typeof v === 'string')
  if (completed.length + remaining.length > 0) {
    return {
      completed: completed.length,
      total: completed.length + remaining.length,
      items: [
        ...completed.map((label) => ({ label, state: 'DONE' as const })),
        ...remaining.map((label, index) => ({ label, state: index === 0 ? 'ACTIVE' as const : 'PENDING' as const })),
      ],
    }
  }
  const items = plan?.items ?? []
  if (items.length === 0) return null
  const steps = plan?.steps ?? []
  const progressItems = items.map((item, index): ProgressItem => {
    const step = steps.find((candidate) => candidate.stepIndex === index)
    const rawStatus = String(step?.status ?? '')
    const state = ['SUCCESS', 'SUCCEEDED', 'COMPLETED', 'SKIPPED'].includes(rawStatus) ? 'DONE'
      : ['FAILED', 'CANCELLED'].includes(rawStatus) ? 'FAILED'
        : index === (plan?.cursor ?? 0) ? 'ACTIVE' : 'PENDING'
    return {
      label: item.summary || item.text || friendlyCapability(item.capabilityId) || `第 ${index + 1} 项`,
      state,
    }
  })
  return {
    completed: progressItems.filter((item) => item.state === 'DONE').length,
    total: progressItems.length,
    items: progressItems,
  }
}

function TaskProgress({ progress }: { progress: ProgressView }) {
  const percent = progress.total === 0 ? 0 : Math.round(progress.completed / progress.total * 100)
  return (
    <section className="task-progress" aria-label="任务进度">
      <div className="experience-heading">
        <strong>办理进度</strong>
        <span>{progress.completed}/{progress.total}</span>
      </div>
      <div className="progress-track" aria-hidden="true">
        <span style={{ width: `${percent}%` }} />
      </div>
      <ol className="todo-list">
        {progress.items.map((item, index) => (
          <li key={`${index}:${item.label}`} className={item.state.toLowerCase()}>
            <span className="todo-marker" aria-hidden="true" />
            <span>{item.label}</span>
            <small>{progressStateLabel(item.state)}</small>
          </li>
        ))}
      </ol>
    </section>
  )
}

function LoopProgress({ loop }: { loop: NonNullable<RecentEntry['loopExecution']> }) {
  const steps = loop.steps ?? []
  return (
    <section className="task-progress" aria-label="排查进度">
      <div className="experience-heading">
        <strong>排查进度</strong>
        <span>{Math.min(loop.iteration, loop.maxIterations)}/{loop.maxIterations}</span>
      </div>
      <ol className="todo-list compact">
        {steps.map((step, index) => {
          const state = loopStepState(step.status)
          return (
            <li key={`${step.stepIndex}:${index}`} className={state.toLowerCase()}>
              <span className="todo-marker" aria-hidden="true" />
              <span>{loopStepLabel(step.actionType, index)}</span>
              <small>{progressStateLabel(state)}</small>
            </li>
          )
        })}
      </ol>
    </section>
  )
}

function SummaryFields({ title, slots, keys = fieldOrder }: {
  title: string
  slots: Record<string, unknown>
  keys?: string[]
}) {
  const fields = keys.flatMap((key) => {
    const value = slots[key]
    if (value == null || value === '' || !fieldLabels[key]) return []
    return [{ key, label: fieldLabels[key], value: formatValue(key, value, slots.currency) }]
  }).slice(0, 10)
  if (fields.length === 0) return null
  return (
    <section className="summary-fields" aria-label={title}>
      <div className="experience-heading"><strong>{title}</strong></div>
      <dl>
        {fields.map((field) => (
          <div key={field.key}>
            <dt>{field.label}</dt>
            <dd>{field.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

interface ResultCardView {
  key: string
  title: string
  fields: Record<string, unknown>
}

function ResultCards({ slots }: { slots: Record<string, unknown> }) {
  const configured = arrayValue(slots.resultCards).flatMap((value, index): ResultCardView[] => {
    const card = objectValue(value)
    const fields = objectValue(card.fields)
    if (Object.keys(fields).length === 0) return []
    return [{
      key: `${String(card.capabilityId ?? '')}:${index}`,
      title: stringValue(card.title) || '办理结果',
      fields,
    }]
  })
  const cards = configured.length > 0
    ? configured
    : [{ key: 'result', title: '办理结果', fields: slots }]
  return <>
    {cards.map((card) => (
      <div className="result-card" key={card.key}>
        <SummaryFields title={card.title} slots={card.fields} />
        <TransactionHistory value={card.fields.transactions} />
      </div>
    ))}
  </>
}

function ProductComparison({ slots }: { slots: Record<string, unknown> }) {
  const cards = arrayValue(slots.resultCards).map(objectValue).filter((card) =>
    Object.keys(objectValue(card.fields)).length > 0,
  ).slice(0, 2)
  if (cards.length !== 2) return null
  const rows = [
    ['domain', '类型'],
    ['riskLevel', '风险等级'],
    ['returnRate', '参考收益率'],
    ['term', '期限'],
  ] as const
  return (
    <section className="product-comparison" aria-label="产品对比">
      <div className="experience-heading"><strong>产品对比</strong></div>
      <div className="comparison-grid" role="table">
        <div role="row" className="comparison-head">
          <span role="columnheader">对比项</span>
          {cards.map((card, index) => <strong role="columnheader" key={index}>
            {stringValue(card.title) || `产品${index + 1}`}
          </strong>)}
        </div>
        {rows.map(([key, label]) => (
          <div role="row" key={key}>
            <span role="rowheader">{label}</span>
            {cards.map((card, index) => <span role="cell" key={index}>
              {stringValue(objectValue(card.fields)[key]) || '—'}
            </span>)}
          </div>
        ))}
      </div>
    </section>
  )
}

interface TransactionRow {
  date: string
  description: string
  amount: string
}

function TransactionHistory({ value }: { value: unknown }) {
  const rows = arrayValue(value).flatMap((item): TransactionRow[] => {
    const row = objectValue(item)
    const date = stringValue(row.date)
    const description = stringValue(row.description)
    const amount = stringValue(row.amount)
    return date && description && amount ? [{ date, description, amount }] : []
  }).slice(0, 20)
  if (rows.length === 0) return null
  return (
    <section className="transaction-history" aria-label="交易明细">
      <div className="experience-heading">
        <strong>最近交易</strong>
        <span>{rows.length} 笔</span>
      </div>
      <ol>
        {rows.map((row, index) => (
          <li key={`${index}:${row.date}:${row.description}:${row.amount}`}>
            <time>{row.date}</time>
            <span>{row.description}</span>
            <strong className={row.amount.startsWith('-') ? 'debit' : 'credit'}>{row.amount}</strong>
          </li>
        ))}
      </ol>
    </section>
  )
}

function RiskNotice({ codes }: { codes: unknown[] }) {
  if (codes.length === 0) return null
  return (
    <div className="risk-notice" role="note">
      涉及资金或账户变更，请在执行前核对关键信息。
    </div>
  )
}

function reviewTitle(phase: string) {
  if (phase === 'SWITCH_REVIEW') return '任务切换'
  if (phase === 'CONFIRM') return '执行确认'
  return '请核对'
}

function clientActionLabel(code: string, slots: Record<string, unknown>) {
  if (code === 'OPEN_MENU') return `打开${typeof slots.menuName === 'string' ? `“${slots.menuName}”` : '菜单'}`
  if (code === 'OPEN_CAPABILITY') return '继续办理'
  if (code === 'CONTACT_SERVICE') return '联系人工服务'
  if (code === 'CHECK_DETAIL') return '查询处理结果'
  return '重试'
}

export function clientActionOutcome(code: string, response: ChatResponse): string {
  const slots = objectValue(response.plan?.slots)
  if (code === 'OPEN_MENU') {
    return `已打开${typeof slots.menuName === 'string' ? `“${slots.menuName}”` : '目标菜单'}`
  }
  if (code === 'OPEN_CAPABILITY') return '已打开办理入口'
  if (code === 'CONTACT_SERVICE') return '已打开人工服务入口'
  if (code === 'CHECK_DETAIL') return '已打开交易明细，请核对实际处理状态。'
  return ''
}

function formatValue(key: string, value: unknown, currency: unknown) {
  if (typeof value === 'boolean') return value ? '已到账' : '未到账'
  if (Array.isArray(value)) return `${value.length} 项`
  if (typeof value === 'object') return '已获取'
  const text = String(value)
  if (key === 'cardType') {
    if (text === 'CREDIT') return '信用卡'
    if (text === 'DEBIT') return '借记卡'
  }
  if (moneyFields.has(key) && typeof currency === 'string' && currency && !text.includes(currency)) {
    return `${currency}${text}`
  }
  return text
}

function progressStateLabel(state: ProgressItem['state']) {
  return state === 'DONE' ? '已完成' : state === 'ACTIVE' ? '处理中' : state === 'FAILED' ? '未完成' : '待处理'
}

function loopStepState(status: string): ProgressItem['state'] {
  if (['SUCCESS', 'SUCCEEDED', 'COMPLETED'].includes(status)) return 'DONE'
  if (['FAILED', 'CANCELLED'].includes(status)) return 'FAILED'
  if (['CLAIMED', 'EXECUTING', 'PROPOSED'].includes(status)) return 'ACTIVE'
  return 'PENDING'
}

function loopStepLabel(actionType: string | null | undefined, index: number) {
  const labels: Record<string, string> = {
    CALL_CAPABILITY: '检查业务状态',
    CALL_KNOWLEDGE: '查询相关知识',
    CALL_NAVIGATION: '查找办理入口',
    DELEGATE_GOAL: '协同领域助手',
    ASK_USER: '等待补充信息',
    FINISH: '整理排查结果',
    HANDOFF: '转接人工处理',
  }
  return labels[actionType ?? ''] ?? `处理第 ${index + 1} 项`
}

function friendlyCapability(capabilityId: string | null | undefined) {
  if (!capabilityId) return ''
  const known: Record<string, string> = {
    'cap.account.balance.query': '查询账户余额',
    'cap.account.card.status.query': '检查卡片状态',
    'cap.payroll.arrival.query': '查询工资到账情况',
    'cap.transfer': '办理转账',
    'cap.fund.product.query': '查询基金产品',
  }
  return known[capabilityId] ?? ''
}

function objectValue(value: unknown): Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
}

function arrayValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function stringArray(value: unknown): string[] {
  return arrayValue(value).filter((item): item is string => typeof item === 'string')
}

function stringValue(value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}
