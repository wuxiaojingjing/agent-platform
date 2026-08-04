package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/**
 * 短路层级（v0.7 §3.3 三级短路 + 活跃慢任务续轮短路）。
 *
 * <p>随 {@code RouteDecision} 一起返回，用于分流出口打点分层与快路径 P95 分层设门。
 */
@Api
public enum ShortCircuitLevel {
    /** 一级：高频直出，命中出口缓存。 */
    L1_CACHE,
    /** 二级：强规则唯一命中直出。 */
    L2_STRONG_RULE,
    /**
     * 三级里的规则直出：句法模版命中标准问，念标准答案而不问模型（FP-1I）。
     *
     * <p><b>名字里没有层号，因为它不是一个成本档</b>。召回照常跑完了（含 embedding 往返），
     * 省下的只是仲裁那一次模型往返。留着召回是为了在 trace 上答得出「这条标准问抢了谁的活」——
     * 一条写宽了的模版会悄悄吃掉本该走能力的流量，没有候选集对照就只表现为「知识问答涨了」。
     */
    STANDARD_ANSWER_RULE,
    /** 三级：进入 Arbitration Skill 模型仲裁。 */
    L3_MODEL,
    /** 续轮短路：跳过完整多路召回，直接交中控续跑。 */
    CONTINUATION,
    /** 未短路，走完整规则仲裁（模型不可用时的回退路径）。 */
    NONE
}
