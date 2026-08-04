package com.huawei.finance.obs;

/**
 * 面客观测最小集的指标与标签名（v0.7 G7、实施架构 §5 第 6 条）。
 *
 * <p>集中定义是为了让打点名字可被搜索、可被评审。散落在各处的字符串字面量会在改名时
 * 悄悄断掉看板，而看板断了没人会收到告警。
 */
public final class AgentMetrics {

    /** 分流出口计数，标签：decision、reasonCode、shortCircuit。 */
    public static final String ARBITRATION_DECISION = "huawei.finance.agent.arbitration.decision";

    /** 三级短路命中层级计数，标签：level。 */
    public static final String SHORT_CIRCUIT = "huawei.finance.agent.fastpath.short_circuit";

    /** 快路径端到端耗时。 */
    public static final String FASTPATH_LATENCY = "huawei.finance.agent.fastpath.latency";

    /**
     * 标准问答命中计数，标签：qaId（FP-1I）。
     *
     * <p>按条计而不是只计总数：标准答案命中即直出，一条写宽了的模版在总数上看不出来，
     * 在按条拆的曲线上会立刻显出「这一条吃掉了大半流量」。
     */
    public static final String STANDARD_ANSWER = "huawei.finance.agent.standard_answer.hit";

    /** 召回阶段耗时，标签：channel（rule / bm25 / semantic / fuse）。 */
    public static final String RECALL_LATENCY = "huawei.finance.agent.fastpath.recall.latency";

    /**
     * 快路径分阶段耗时，标签：phase（rewrite / recall / arbitration）。
     *
     * <p>与 {@link #FASTPATH_LATENCY} 是两回事：后者只给总数，一次变慢无法回答慢在哪。
     * 而这条链路上「改写」是本地 CPU、「召回」含 embedding 往返、「仲裁」含大模型往返，
     * 三者劣化的处置方式完全不同——扩容、摘通道、降级到规则——只看总延迟一个都定不了。
     */
    public static final String PHASE_LATENCY = "huawei.finance.agent.fastpath.phase.latency";

    /** 模型网关调用耗时，标签：purpose、outcome。外部依赖预算单列（实施架构 §4.4 规则 3）。 */
    public static final String GATEWAY_LATENCY = "huawei.finance.agent.gateway.latency";

    /**
     * 流式 chat 首帧耗时（FP-63），标签：purpose、outcome、modelVersion、promptVersion。
     *
     * <p>首帧 = 收到第一条非空 SSE {@code data:} 的时刻，对应外部样本的
     * {@code firstFrameRcvTime}。它回答的是「网关慢还是模型慢」的前半截：
     * 首帧就很长多半是建连 / 排队 / TTFT 前的供应侧问题。
     */
    public static final String GATEWAY_FIRST_FRAME = "huawei.finance.agent.gateway.first_frame";

    /**
     * 流式 chat 首个有效 token 耗时（FP-63），标签同 {@link #GATEWAY_FIRST_FRAME}。
     *
     * <p>「有效」指 {@code delta.content} 非空。role 声明帧、空 delta 不算——那些不是用户
     * 看得到的字，记进去会把「模型开始吐字」的时刻往后推，掩盖真 TTFT。
     */
    public static final String GATEWAY_FIRST_TOKEN = "huawei.finance.agent.gateway.first_token";

    /**
     * 流式 chat 平均每 completion token 耗时（FP-63），标签同 {@link #GATEWAY_FIRST_FRAME}。
     *
     * <p>用服务端回报的 {@code usage.completion_tokens} 做分母；没有 usage 就不打这条，
     * 绝不拿字符数冒充 token——那是一个精确的错数，会把看板校准带偏。
     */
    public static final String GATEWAY_AVG_TOKEN = "huawei.finance.agent.gateway.avg_token";

    /** 单请求网关往返次数分布（用途序列见 RequestContext.gatewayCalls）。 */
    public static final String GATEWAY_ROUND_TRIPS = "huawei.finance.agent.gateway.round_trips";

    /**
     * @deprecated ADR-003 取消次数硬上限后不再写入；保留常量以免旧看板查询直接 404 语义，
     *             新代码不得再 increment。
     */
    @Deprecated
    public static final String GATEWAY_BUDGET_EXCEEDED = "huawei.finance.agent.gateway.budget_exceeded";

    /**
     * 服务端回报的单次输入 token 数，标签：purpose。
     *
     * <p>往返次数与单价失控可以同时成立：外部同类系统一次工具选择付到 5654–7913 输入
     * token（§2.7.1），只因把候选的完整 schema 都塞进了 prompt。只盯往返次数看不见这件事。
     */
    public static final String GATEWAY_PROMPT_TOKENS = "huawei.finance.agent.gateway.prompt_tokens";

    /** 仲裁 prompt 的字符数分布。发出前可算，用于在无密钥环境里也能守住体积回归。 */
    public static final String ARBITRATION_PROMPT_CHARS = "huawei.finance.agent.arbitration.prompt_chars";

    /** 仲裁 prompt 被裁剪的计数，标签：reason（CANDIDATE_CAP / CHAR_BUDGET / OVERSIZED）。 */
    public static final String ARBITRATION_PROMPT_TRIMMED = "huawei.finance.agent.arbitration.prompt_trimmed";

    /**
     * 仲裁选择与检索排名的一致性计数，标签：outcome（AGREED / OVERRULED）。
     *
     * <p>OVERRULED 表示模型没选检索给出的第一名。这个比例本身不是故障指标——模型推翻检索
     * 正是引入它的目的；有意义的是它的**变化**：突然升高说明融合权重或阈值与模型判断
     * 出现了系统性分歧，而这件事在出口分布上完全看不出来（出口可能一个都没变）。
     */
    public static final String ARBITRATION_VS_RECALL = "huawei.finance.agent.arbitration.vs_recall";

    /**
     * FP-66 实例亲和错配计数（ADR-004）。
     *
     * <p>非零表示续办或开计划落到了非 owner 实例。部署粘滞失效时会涨——要比静默半残
     * 跑 DeepAgent Workspace 更好暴露。
     */
    public static final String SESSION_AFFINITY_MISMATCH = "huawei.finance.agent.session.affinity.mismatch";

    /** Slow Path 规划锚定结果，标签：outcome。 */
    public static final String SLOWPATH_PLANNING = "huawei.finance.agent.slowpath.planning";

    /** 降级计数，标签：component、reason。 */
    public static final String DEGRADED = "huawei.finance.agent.degraded";

    /** 模板渲染结果计数，标签：templateKey、outcome（HIT / FALLBACK）。 */
    public static final String TEMPLATE_RENDER = "huawei.finance.agent.response.template_render";

    /**
     * 答案侧审核拒绝计数，标签：outcome（BLOCK）、reason（拒绝码）。
     *
     * <p>只在拒绝时打点，放行不打——A 线全模板输出，放行是常态，给常态计数换不来信息，
     * 只会让每次请求多一次 counter 查找。这条非零意味着有面客答案被拦下并降成了兜底文案，
     * 属于要人看的事件，不是流量指标。
     */
    public static final String ANSWER_AUDIT = "huawei.finance.agent.response.answer_audit";

    /**
     * 按话题触发的合规提示计数，标签：reason（话题码）。
     *
     * <p>与能力风险等级并列的那条通道（§2.7.9）。「买什么基金好」不触发任何 R2 能力，
     * 却落在持牌业务里，只看 riskLevel 的看板永远看不见这类请求。
     */
    public static final String COMPLIANCE_TOPIC = "huawei.finance.agent.response.compliance_topic";

    /**
     * 租户头校验拒绝计数，标签：reason（MISSING_USER_ID / MISSING_SPACE_ID / USER_ID_MISMATCH）。
     *
     * <p>三个原因要分开看：前两个多半是某条渠道接入时漏配了头，属对接问题；
     * 而 {@code USER_ID_MISMATCH} 是请求体里的 userId 与网关注入的头不一致——
     * 那要么是上游有 bug，要么是有人在试探，两者都不该混在一条总数里。
     */
    public static final String TENANT_HEADER_REJECTED = "huawei.finance.agent.tenant.header_rejected";

    /** 任务状态迁移计数，标签：from、to。 */
    public static final String TASK_TRANSITION = "huawei.finance.agent.task.transition";

    public static final String ENTRY_ROUTE = "huawei.finance.agent.route.decision";
    public static final String CONTINUATION_DECISION = "huawei.finance.agent.continuation.decision";
    public static final String CONTINUATION_MODEL = "huawei.finance.agent.continuation.model";
    public static final String CONTINUATION_MODEL_CACHE = "huawei.finance.agent.continuation.model_cache";
    public static final String FOCUS_TRANSITION = "huawei.finance.agent.focus.transition";
    public static final String SWITCH_TRANSITION = "huawei.finance.agent.switch.transition";
    public static final String PENDING_GOAL_TRANSITION = "huawei.finance.agent.pending_goal.transition";
    public static final String STATIC_PLAN_STEP = "huawei.finance.agent.static_plan.step";
    public static final String LOOP_ACTION = "huawei.finance.agent.loop.action";
    public static final String LOOP_OBSERVATION = "huawei.finance.agent.loop.observation";

    /**
     * 领域 Agent 超时计数，标签：capability、outcome（PARTIAL / RETRYABLE）。
     *
     * <p>outcome 区分的是「结果未知」与「可安全重试」。有副作用的能力超时后中断线程并不能
     * 撤回已发出的操作，那时说「失败请重试」会诱导用户重复转账。
     */
    public static final String AGENT_TIMEOUT = "huawei.finance.agent.agent.timeout";

    /**
     * 能力卡声明超时超出主控上限、被压到上限的计数，标签：capability。
     *
     * <p>非零说明有卡上写的数其实没生效。静默改写而不留痕，下次没人知道。
     */
    public static final String AGENT_TIMEOUT_CLAMPED = "huawei.finance.agent.agent.timeout_clamped";

    /**
     * 抽到但未被能力卡声明、因而未提交给领域 Agent 的槽位计数，标签：capability、slot。
     *
     * <p>这个数持续走高，说明用户一直在说某个参数而领域方没有声明它——是催领域方补声明的
     * 依据，不是故障。没有它，「主 Agent 不替你猜」这条规矩就只剩沉默，谁也不知道漏了什么。
     */
    public static final String SLOT_NOT_OWNED = "huawei.finance.agent.slots.not_owned";

    /**
     * 仲裁模型回填的槽位计数，标签：capability、slot、outcome（ACCEPTED / OVERRIDDEN / REJECTED）。
     *
     * <p>OVERRIDDEN 表示正则也抽到了同一槽位并以正则为准，REJECTED 表示模型填了能力卡
     * 未声明的槽位。三者的比例决定了模型抽槽到底值不值得开。
     */
    public static final String SLOT_MODEL_FILL = "huawei.finance.agent.slots.model_fill";

    /**
     * R2 能力下发时把用户原句一并交给领域方的留痕，标签：capability。
     *
     * <p>R2 是要动钱的那一档。领域方在执行前会依据「用户到底说了什么」做自己那一道风险复核，
     * 而主 Agent 手上有两份文本：用户原话，和为了检索而改写归一过的版本。下发改写版会造成
     * 一种很难发现的错误——事后审计里躺着一句用户从未说过的话，且它读起来完全合理。
     *
     * <p>所以 R2 一律下发原句，并在这里留痕。留痕不是为了统计调用量，是为了让「这一笔的
     * 委托依据是原句」成为一条可以被查出来的事实，而不是一句写在注释里的承诺。
     */
    public static final String DELEGATE_R2_RAW_GOAL = "huawei.finance.agent.delegate.r2_raw_goal";

    /**
     * ContextLease 因超预算裁掉的条目数（FP-28）。
     *
     * <p>裁剪是预期行为不是故障，但它的**速率**是预算配置是否合身的唯一外部信号：
     * 常年为零说明预算给大了，白付 token；持续走高说明真实会话比预算长，
     * 模型每轮都在缺一段历史的情况下做判断，而这件事在出口分布上一点都看不出来。
     */
    public static final String CONTEXT_TRIMMED = "huawei.finance.agent.context.trimmed";

    /**
     * 签发降级租约的计数（FP-28）。
     *
     * <p><b>非零即意味着有请求被禁止动账</b>，属于必须告警的事件而不是流量指标。
     * 两种成因都在这里：历史读不到，以及已确认事实本身就超了预算。
     */
    public static final String CONTEXT_DEGRADED = "huawei.finance.agent.context.degraded";

    /**
     * 因上下文不可信而被拦下的有副作用操作，标签：capability。
     *
     * <p>与 {@link #CONTEXT_DEGRADED} 分开：签发了降级租约不等于真拦到了什么，
     * 大量只读请求照常降级通过。这条非零才说明确实有一笔要动钱的操作被挡住了，
     * 那是要有人去看的。
     */
    public static final String CONTEXT_SIDE_EFFECT_BLOCKED = "huawei.finance.agent.context.side_effect_blocked";

    /** Contextual rewrite outcomes; tags: outcome, implementation. */
    public static final String CONTEXT_REWRITE = "huawei.finance.agent.context.rewrite";

    /** Parent-side ContextDelta merge outcomes; tags: outcome, reason. */
    public static final String CONTEXT_DELTA_MERGE = "huawei.finance.agent.context.delta.merge";

    /** A2A 委托投递，标签：agent、mode、outcome（架构草案 v0.2 §6）。 */
    public static final String A2A_DELEGATION = "huawei.finance.agent.a2a.delegation";

    /** A2A 客户端、中转网关、目标服务端分段耗时，标签：segment、outcome。 */
    public static final String A2A_SEGMENT_LATENCY = "huawei.finance.agent.a2a.segment.latency";

    /** A2A 明确失败，标签：segment、reason、outcome。 */
    public static final String A2A_FAILURE = "huawei.finance.agent.a2a.failure";

    /**
     * 同一 {@code delegationId} 二次到达，标签：agent。
     *
     * <p>单独一条而不是并进 {@link #A2A_DELEGATION} 的 outcome：重投是**上游行为**，
     * 不是业务结局。混在一起之后「重投了多少次」这个问题查不出来，
     * 而这个数持续非零正是上游重试逻辑写错了的信号——那种错的表现是重复转账。
     */
    public static final String A2A_DELEGATION_REPLAYED = "huawei.finance.agent.a2a.delegation.replayed";

    /** 因投错域而改投，标签：from、to。§7.1 改投上限一次。 */
    public static final String A2A_REROUTED = "huawei.finance.agent.a2a.rerouted";

    public static final String TAG_CAPABILITY = "capability";
    /** A2A 目标节点 agentId。 */
    public static final String TAG_AGENT = "agent";
    /** 委托模式：TASK / GOAL。 */
    public static final String TAG_MODE = "mode";
    public static final String TAG_SEGMENT = "segment";
    public static final String TAG_SLOT = "slot";
    /** 标准问答条目 id，见 {@link #STANDARD_ANSWER}。 */
    public static final String TAG_QA_ID = "qaId";

    public static final String TAG_DECISION = "decision";
    public static final String TAG_REASON_CODE = "reasonCode";
    public static final String TAG_SHORT_CIRCUIT = "shortCircuit";
    public static final String TAG_LEVEL = "level";
    public static final String TAG_CHANNEL = "channel";
    /** 见 {@link #PHASE_LATENCY}。 */
    public static final String TAG_PHASE = "phase";
    public static final String TAG_PURPOSE = "purpose";
    public static final String TAG_OUTCOME = "outcome";
    /** 见 {@link #GATEWAY_FIRST_FRAME}：仲裁模型标识。 */
    public static final String TAG_MODEL_VERSION = "modelVersion";
    /** 见 {@link #GATEWAY_FIRST_FRAME}：仲裁 Skill / 提示词版本。 */
    public static final String TAG_PROMPT_VERSION = "promptVersion";
    public static final String TAG_COMPONENT = "component";
    public static final String TAG_REASON = "reason";
    public static final String TAG_TEMPLATE_KEY = "templateKey";
    public static final String TAG_FROM = "from";
    public static final String TAG_TO = "to";

    private AgentMetrics() {
    }
}
