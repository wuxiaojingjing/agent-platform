package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/**
 * 出口原因码。
 *
 * <p>前六项是 v0.7 附录 B 的冻结基线。其余为本工程按 §3.2/§3.3 的运行强制要求补充：
 * 续轮短路与三级短路都被明确要求「产出 RouteDecision 并标注 reasonCode」，
 * 容量冲突降级也要求打点 reasonCode，基线六项无法表达这些路径。
 *
 * <p>补充项须在 M1 接口冻结评审时一并冻结，不得继续私自扩充——原因码一旦发散，
 * 分流出口看板就失去可比性。
 */
@Api
public enum ReasonCode {

    // ---- v0.7 附录 B 基线 ----
    /** Top1 与 Top2 间隔不达标。 */
    LOW_MARGIN,
    /** 无候选。 */
    NO_CANDIDATE,
    /** 缺少必填槽位。 */
    MISSING_SLOT,
    /** 多意图。 */
    MULTI_INTENT,
    /** 跨域或条件依赖。 */
    CROSS_DOMAIN,
    /** 越权、未开放能力或安全策略拦截。 */
    POLICY_BLOCK,

    // ---- 本工程补充，M1 随基线一并冻结 ----
    /** Top1 达标且间隔达标、槽位完整，正常直出。 */
    HIGH_CONFIDENCE,
    /** 一级短路：命中出口缓存。 */
    SHORT_CIRCUIT_CACHE,
    /** 二级短路：强规则唯一命中。 */
    SHORT_CIRCUIT_STRONG_RULE,
    /** 续轮短路：本轮为活跃任务的补充/纠正/确认/取消。 */
    CONTINUATION,
    /** 模型仲裁不可用或输出非法，已回退规则仲裁（v0.7 §3.3）。 */
    ARBITRATION_FALLBACK,
    /** 容量冲突降级（v0.7 §3.2 容量冲突裁决），计入容量事故而非正常短路。 */
    CAPACITY_DEGRADED,
    /** 澄清轮数已达上限仍不收敛。 */
    CLARIFY_EXHAUSTED,
    /** R2 交易需执行前显式确认。 */
    CONFIRMATION_REQUIRED,
    /**
     * 二级半短路：句法模版命中标准问，答案直接来自标准问答库（FP-1I）。
     *
     * <p>与 {@code HIGH_CONFIDENCE} 分开计：后者是「选中了一个能力去办」，
     * 这一条是「答了一句话，什么也没办」。混在一起看板上的直出率会虚高，
     * 而这两类的失败形态完全不同——一个办错事，一个说错话。
     */
    STANDARD_ANSWER,
    REVIEW_REQUIRED,
    RESULT_RULE,
    AFTER_OBSERVATION,
    OPEN_ENDED_DIAGNOSIS,
    INVALID_MODEL_OUTPUT,
    /** The utterance refers to an item that is absent from authoritative visible context. */
    UNRESOLVED_REFERENCE,
    /** Products were grounded successfully but policy forbids comparison across their types. */
    INCOMPARABLE_PRODUCT_TYPE,
    LOOP_DISABLED,
    SWITCH_REQUIRED,
    RESUME_REQUIRED
}
