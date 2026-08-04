package com.huawei.finance.common.event;

/**
 * 判断一句话是否携带「新的可执行意图」的探针。
 *
 * <p>事件分类要区分 SUPPLEMENT 与 TOPIC_SWITCH，必须知道新输入是不是另起了一件事，
 * 而这依赖意图/领域词表——那是快路径的资产，不属于共享库。共享库因此只声明接口，
 * 由 {@code intent-fastpath} 用同义词表与关键词规则实现。
 *
 * <p>实现必须是纯本地、无网络的：续轮短路的价值就在于省掉召回与网关往返，
 * 探针一旦发起远程调用，短路就失去意义。
 */
@FunctionalInterface
public interface IntentSignalProbe {

    /**
     * @param normalizedQuery 归一化后的用户输入
     * @param activeDomain    当前活跃任务的领域，可为 null
     * @return 探测结论
     */
    Signal probe(String normalizedQuery, String activeDomain);

    /** 探测结论。 */
    enum Signal {
        /** 未识别出可执行意图，多半是补充/确认类短输入。 */
        NONE,
        /** 识别出可执行意图，且与活跃任务同领域。 */
        SAME_DOMAIN_INTENT,
        /** 识别出可执行意图，且属于其他领域。 */
        OTHER_DOMAIN_INTENT
    }

    /** 无活跃任务或未装配实现时的默认探针。 */
    IntentSignalProbe NONE = (q, d) -> Signal.NONE;
}
