package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/**
 * 附录 B 中取值较简单的枚举集合。
 *
 * <p>放在一个文件里是因为它们没有行为，只是取值域；四出口、原因码、风险等级这类带判定
 * 语义的枚举则各自独立成文件。
 */
@Api
public final class Enums {

    private Enums() {
    }

    /**
     * 子意图与前序子意图之间的关系。
     *
     * <p>三者下游的执行策略并不相同，所以不能合并成一个「多意图」布尔值：并行的可以同时下发，
     * 顺序的必须拿前一步的输出当输入，条件的还要先判断条件成立与否——「不足就别转」里的转账
     * 是否执行，取决于查余额的结果。切片计划 §2.7.2 把「依赖与并列分开判」列为 S4 的硬要求，
     * 依据也在这里。
     */
    public enum IntentRelation {
        /** 与前序无关，可独立下发。 */
        PARALLEL,
        /** 须在前序完成之后执行，但不附加条件。 */
        SEQUENTIAL,
        /** 须在前序完成之后，且前序结果满足条件时才执行。 */
        CONDITIONAL
    }

    /** {@code RecallResult.domainRouting.routingMode}。 */
    public enum RoutingMode {
        SINGLE, MULTI, UNKNOWN
    }

    /** {@code RecallResult.candidates[].candidateType}。 */
    public enum CandidateType {
        INTENT, TOOL, SKILL, WORKFLOW, AGENT
    }

    /** {@code CapabilityCard.type}。 */
    public enum CapabilityType {
        TOOL, SKILL, WORKFLOW, AGENT
    }

    /** {@code CapabilityCard.granularity}（两层粒度见 v0.7 §3.6.2）。 */
    public enum Granularity {
        AGENT, TOOL, SKILL, WORKFLOW
    }

    /** {@code CapabilityCard.idempotency}。 */
    public enum Idempotency {
        REQUIRED, SUPPORTED, NONE
    }

    /** {@code CapabilityCard.status}。 */
    public enum CapabilityStatus {
        ACTIVE, GRAY, DISABLED
    }

    /** Agent 资产的建设状态；与发布状态分开，SCAFFOLD 永远不可被当作可执行实现。 */
    public enum ImplementationStatus {
        IMPLEMENTED, SCAFFOLD
    }

    /**
     * {@code CapabilityCard.slotOwner}：谁负责把参数问齐。
     *
     * <p>这是一条组织边界，不只是技术开关。能力卡上声明了必填槽位，等于领域方把
     * 「这些参数叫什么、缺了要问」的口径交给了主 Agent；没声明，就意味着领域方保留
     * 自己收参的权利，主 Agent 不得替它猜——猜错收款人的代价由领域方承担，
     * 而它连这个字段该怎么解释都没表过态。
     */
    public enum SlotOwner {
        /** 主 Agent 提参并澄清，只限能力卡声明过的槽位。 */
        MAIN,
        /** 领域子 Agent 自行收参，主 Agent 原句下发、不提交任何抽取结果。 */
        AGENT
    }

    /**
     * {@code CapabilityCard.guardrailOwner}：谁负责校验护栏。
     *
     * <p>与 {@link SlotOwner} 是两回事，容易混。SlotOwner 说的是「谁把参数问齐」，
     * 这个说的是「谁在执行前拦」。二者可以分离：领域方自己收参（AGENT），
     * 权限与限额校验却仍可能在主控（MAIN）。
     *
     * <p>缺省 {@code DOMAIN} 是刻意的。§2.7.4 那次 74 秒派发的复盘结论是**不改提参归属**——
     * 申购的合规校验、风险测评匹配、单笔限额都在基金域，主控替它抽一个 amount 并不能让护栏更准，
     * 反而会造成「主控以为自己管了」的错觉。因此默认由领域方管，声明 MAIN 才归主控。
     *
     * <p><b>S2 阶段只留位，尚未启用</b>：现有护栏对所有能力一律校验，不看这个字段。
     * 现在就把它加进契约，是为了让领域方在补声明时能顺手表态，而不是等启用那天再去逐张卡追问。
     */
    public enum GuardrailOwner {
        /** 主控护栏校验其 requiredSlots、权限与限额。 */
        MAIN,
        /** 领域方自行校验，主控只留痕不阻断（见 huawei.finance.agent.delegate.r2_raw_goal）。 */
        DOMAIN
    }

    /** {@code UnifiedTask.source}：本任务由快路径还是慢路径完成意图识别。 */
    public enum TaskSource {
        FAST_PATH, SLOW_PATH
    }

    /** 动作进入当前 Agent 的调用来源，与意图识别路径正交。 */
    public enum InvocationOrigin {
        LOCAL, A2A
    }

    /** {@code guardrailCheck.status}。 */
    public enum GuardrailStatus {
        PENDING, PASSED, FAILED
    }

    /** {@code TaskResult.status}。 */
    public enum TaskStatus {
        SUCCESS, PARTIAL, FAILED, CANCELLED, NEED_USER
    }

    /** {@code TaskResult.failureClass}。 */
    public enum FailureClass {
        RETRYABLE, NEED_USER, PARTIAL, FATAL, CANCELLED, NONE
    }

    /** {@code ResponsePlan.responsePhase}。 */
    public enum ResponsePhase {
        ACK, PROGRESS, CLARIFY, REVIEW, SWITCH_REVIEW, CONFIRM, FINAL, ERROR
    }

    /** {@code ResponsePlan.renderMode}：模型只决定或改写面客文本，不参与动作与状态决策。 */
    public enum RenderMode {
        TEMPLATE, MODEL_SELECT, POLISH, GENERATE
    }

    /**
     * 历史轮里那次工具执行的结局（FP-28，词表取自 §2.7.1 现网样本）。
     *
     * <p>这套取值刻意不允许自由文本。历史轮回灌给模型时，「上一步办到哪一步了」若写成
     * 一句自然语言描述，同一个状态会出现十几种说法，模型得先去理解它，既费 token 又生歧义；
     * 而这一段恰恰是判断「该续办还是该重来」的依据，理解偏了整轮就错。
     *
     * <p>{@code wireName} 是回灌进 prompt 时的字面量，取现网跑过的写法而不是 Java 常量名——
     * 这套词表在现网样本里已被模型消费过，换写法等于把已验证的东西重新赌一次。
     */
    public enum ToolOutcome {
        /** 执行成功，结果可信。 */
        SUCCEEDED("succeed"),
        /** 执行失败。失败原因不进上下文，只进审计——模型不需要据此重试。 */
        FAILED("fail"),
        /** 没有能力承接。与 FAILED 分开：前者不该重试，后者可能可以。 */
        UNSUPPORTED("unsupported tool"),
        /** 需要再调一个能力才能回答。 */
        ADDITIONAL_TOOL("use additional tool"),
        /** 仍在执行，本轮拿不到终值。 */
        ON_GOING("on-going");

        private final String wireName;

        ToolOutcome(String wireName) {
            this.wireName = wireName;
        }

        /** 回灌进上下文时的字面量。 */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * 历史轮结束时挂在用户身上的待办（FP-28，词表取自 §2.7.1 现网样本）。
     *
     * <p>与 {@link ToolOutcome} 正交：工具可能成功了，但仍在等用户确认。
     * 三种取值对应三种截然不同的续轮解读——缺参数、要选一个、要点头，
     * 混成一个「等待用户」会让续轮短路无法判断该拿本轮输入去填什么。
     */
    public enum PendingAction {
        /** 缺必填槽位，等用户补。 */
        FILLING_SLOT("fillingSlot"),
        /** 给了候选项，等用户选一个。 */
        SELECT("select"),
        /** 参数齐了，等用户确认执行（R2 必经）。 */
        CONFIRM("confirm"),
        /** 参数齐了，等用户审阅并接受方案。 */
        REVIEW("review"),
        /** 没有待办，该轮已闭环。 */
        NONE("none");

        private final String wireName;

        PendingAction(String wireName) {
            this.wireName = wireName;
        }

        /** 回灌进上下文时的字面量。 */
        public String wireName() {
            return wireName;
        }
    }

    /** {@code TaskIntent.taskType}。 */
    public enum TaskType {
        QUERY, COMPARE, TRANSACT, MULTI_TASK, PLAN
    }

    /** {@code TaskIntent.suggestedExecutionMode}。 */
    public enum ExecutionMode {
        DIRECT, PLAN, CLARIFY
    }
}
