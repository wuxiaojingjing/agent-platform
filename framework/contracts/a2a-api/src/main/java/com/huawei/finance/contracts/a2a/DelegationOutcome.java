package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Api;

/**
 * 委托结局（架构草案 v0.2 §7、v0.3 §7.1）。
 *
 * <p>{@link #NOT_MINE} 与 {@link #DOMAIN_NOT_OPEN} 必须分开，理由是**归因**:
 * 合并成一种「失败」之后，入口判错会永远被计成「域没做完」，
 * 而这两件事的 Owner 和修法完全不同——前者要调域路由资产与阈值，后者要交付节点。
 * 这是 v0.3 §7.1 新增的一类失败:薄入口带来的「投错域」，{@code NEED_USER} 表达不了它。
 */
@Api
public enum DelegationOutcome {

    /** 办成了。事实在 {@link DelegationReceipt#facts()} 里，且必须是结构化字段。 */
    SUCCEEDED,

    /**
     * 域内信息不全，本次委托到此终止。
     *
     * <p>补参后是**新** {@code delegationId}，不是续用旧的（§7 第 2 条）。
     * 中间层不得对它填默认值。
     */
    NEED_USER,

    /**
     * 这件事不属于本域——入口投错了。
     *
     * <p>入口可按域路由 Top-K 改投下一个域，**只改投一次**，并计入委托预算。
     * 遍历 Top-K 等于让一次判错的代价变成 K 次委托的延迟与成本，
     * 而第二次还错说明域路由本身有问题，该走澄清问用户，不该继续猜。
     */
    NOT_MINE,

    /**
     * 属于本域，但本域尚未建成（现有 Scaffold 行为）。
     *
     * <p>不改投，走能力未开放 / 转人工。不得假成功、不得回假数据。
     */
    DOMAIN_NOT_OPEN,

    /**
     * 整单致命。缺信封、版本不符、深度超限、环路、纯执行器收到 GOAL 都落这里。
     *
     * <p>不静默截断——静默截断要到压测才暴露。
     */
    FATAL,

    /**
     * 结果未知。有副作用的能力超时后是这个，不是 FAILED。
     *
     * <p>沿用设计约束 11:中断线程撤不回一笔已发出的转账。上游**不得**自动重试。
     */
    PARTIAL
}
