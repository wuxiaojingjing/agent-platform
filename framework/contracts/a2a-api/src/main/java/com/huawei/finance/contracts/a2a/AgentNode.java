package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Spi;

/**
 * A2A 可寻址的 Agent 节点（架构草案 v0.3 §5.2）。
 *
 * <p><b>跨 A2A 的接收方必须是 AgentNode。</b>{@code DomainAgent} 只挂在某个节点的
 * {@code LocalCapabilityExecutor} 下,不得再作为中控进程里「按域分治的对外主接口」——
 * 那是现状过渡，不是目标态。
 *
 * <p>纯执行器不出现在 A2A 路由表:AgentCard 只为 1+26 个 AgentNode 生成（§6）。
 *
 * <p>实现方要自己保证:
 * <ul>
 *   <li>{@link #handle} 对同一 {@code delegationId} 二次到达返回首次结果——
 *       不重新建档、不重跑护栏，包括首次结果是 {@code PARTIAL} 的情况（§6.2 第 3 条）;
 *   <li>不属于本域的委托回 {@link DelegationOutcome#NOT_MINE}，尚未建成回
 *       {@link DelegationOutcome#DOMAIN_NOT_OPEN}，两者不合并（§7.1）;
 *   <li>本域护栏每层各自重跑,上游确认只作为事实证据,不替代本地判定（§6.4）。
 * </ul>
 */
@Spi
public interface AgentNode {

    /** 本节点的 agentId:入口是 {@code mobile-banking-assistant}，科技域是 {@code agent.<tech_code>}。 */
    String agentId();

    /**
     * 本节点是否自治——能不能接 GOAL。
     *
     * <p>纯执行器返回 false，收到 GOAL 一律拒绝而不是尽力猜一个能力去执行（§6.1）。
     */
    default boolean autonomous() {
        return true;
    }

    /**
     * 处理一次委托。
     *
     * <p>不得抛异常穿透到网关:异常穿透会让委托既没有回执也没有终态，
     * 上游只能靠超时发现,而超时的语义是「结果未知」,比一份 FATAL 回执模糊得多。
     */
    DelegationReceipt handle(DelegationEnvelope envelope);
}
