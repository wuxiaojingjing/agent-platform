/**
 * 控制台展示用的人话标签。
 *
 * 后端照旧吐枚举；这里只负责页面可读性，不改契约。未知值原样回落，
 * title 里挂原始枚举方便对账。
 */

const DECISION: Record<string, string> = {
  DIRECT_KNOWLEDGE: '知识直答',
  NAVIGATION: '菜单跳转',
  EXECUTE_CAPABILITY: '执行能力',
  START_WORKFLOW: '启动工作流',
  CLARIFY: '澄清',
  STATIC_PLAN: '固定计划',
  DELEGATE_GOAL: '目标委托',
  START_LOOP: '启动 Agent Loop',
  RESUME_TASK: '继续任务',
  RESUME_LOOP: '继续 Loop',
  CANCEL: '取消',
  REJECT: '拒绝',
  HANDOFF: '转人工',
}

const REASON: Record<string, string> = {
  HIGH_CONFIDENCE: '高置信直出',
  LOW_MARGIN: '需要确认具体业务',
  NO_CANDIDATE: '无候选',
  MISSING_SLOT: '缺槽',
  MULTI_INTENT: '多意图',
  CROSS_DOMAIN: '跨域 / 条件依赖',
  POLICY_BLOCK: '策略拦截',
  SHORT_CIRCUIT_CACHE: '出口缓存命中',
  SHORT_CIRCUIT_STRONG_RULE: '强规则直出',
  CONTINUATION: '续轮',
  ARBITRATION_FALLBACK: '仲裁回退规则',
  CAPACITY_DEGRADED: '容量降级',
  CLARIFY_EXHAUSTED: '澄清耗尽',
  CONFIRMATION_REQUIRED: '需确认',
  STANDARD_ANSWER: '标准问答',
  UNRESOLVED_REFERENCE: '上下文引用无法解析',
}

const EXIT_PATH: Record<string, string> = {
  RECALL_ARBITRATION: '召回 + 仲裁',
  CONTINUATION: '续轮',
  L1_CACHE: 'L1 出口缓存',
  L2_STRONG_RULE: 'L2 强规则',
  STANDARD_ANSWER: '标准问答',
  UNKNOWN: '未知',
}

const ARBITRATED_BY: Record<string, string> = {
  MODEL: '模型仲裁',
  RULE_FALLBACK: '规则回退',
  SHORT_CIRCUIT: '短路直出',
}

const EVENT: Record<string, string> = {
  NEW_TASK: '新任务',
  SUPPLEMENT: '补充信息',
  CORRECTION: '纠正',
  CANCEL: '取消',
  NEW_PARALLEL_TASK: '并行新任务',
  TOPIC_SWITCH: '切换话题',
  CONFIRMATION: '确认',
}

const PHASE: Record<string, string> = {
  rewrite: '改写',
  recall: '召回',
  arbitration: '仲裁',
}

const GATEWAY: Record<string, string> = {
  embedding: '向量',
  arbitration: '仲裁',
  rewrite: '改写',
  rerank: '重排',
  condition: '条件判定',
}

/** UUID / 长 id 头尾截断，全量留给 title。 */
export function shortId(id: string | null | undefined): string {
  if (!id) return '—'
  return id.length <= 20 ? id : `${id.slice(0, 13)}…${id.slice(-4)}`
}

export function decisionLabel(v: string | null | undefined): string {
  if (!v) return '—'
  return DECISION[v] ?? v
}

export function reasonLabel(v: string | null | undefined): string {
  if (!v) return '—'
  return REASON[v] ?? v
}

export function exitPathLabel(v: string | null | undefined): string {
  if (!v) return '—'
  return EXIT_PATH[v] ?? v
}

export function arbitratedByLabel(v: string | null | undefined): string {
  if (!v) return '—'
  return ARBITRATED_BY[v] ?? v
}

export function eventLabel(v: string | null | undefined): string {
  if (!v) return '—'
  return EVENT[v] ?? v
}

export function phaseLabel(v: string): string {
  return PHASE[v] ?? v
}

export function gatewayLabel(v: string): string {
  return GATEWAY[v] ?? v
}

/**
 * 短路层级的人话。
 *
 * {@code L3_MODEL} 是「一路走到模型仲裁」，也就是没短路；直接把枚举摆出来
 * 会被读成「在 L3 被短路了」，跟旁边的 exitPath 打架。
 */
export function shortCircuitLabel(level: string | null | undefined): string {
  if (level == null) return '—'
  switch (level) {
    case 'NONE':
    case 'L3_MODEL':
      return '未短路 · 走完整链路'
    case 'L1_CACHE':
      return '短路 L1 · 出口缓存命中'
    case 'L2_STRONG_RULE':
      return '短路 L2 · 强规则直出'
    case 'STANDARD_ANSWER_RULE':
      return '标准问答直出'
    case 'CONTINUATION':
      return '续轮短路'
    default:
      return `短路 ${level}`
  }
}

/** 指标条、分布图用：按已知字典翻，未知原样。 */
export function metricKeyLabel(key: string): string {
  return (
    DECISION[key]
    ?? REASON[key]
    ?? EXIT_PATH[key]
    ?? ARBITRATED_BY[key]
    ?? EVENT[key]
    ?? PHASE[key]
    ?? GATEWAY[key]
    ?? ({
      AGREED: '与召回一致',
      OVERRULED: '推翻 top1',
      HIT: '命中模板',
      FALLBACK: '兜底模板',
      SUCCESS: '成功',
      FAILED: '失败',
      NEED_USER: '待用户',
      CONFIRM_PENDING: '待确认',
      CLARIFY_PENDING: '待澄清',
      RUNNING: '执行中',
      CANCELLED: '已取消',
      L1_CACHE: 'L1 出口缓存',
      L2_STRONG_RULE: 'L2 强规则',
      L3_MODEL: '未短路 · 模型仲裁',
      STANDARD_ANSWER_RULE: '标准问答',
      CONTINUATION: '续轮',
      NONE: '未短路',
    } as Record<string, string>)[key]
    ?? key
  )
}
