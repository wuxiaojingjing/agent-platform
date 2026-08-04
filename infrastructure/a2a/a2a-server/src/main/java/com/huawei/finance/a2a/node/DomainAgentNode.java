package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 域节点适配器：把一个科技域包装成 A2A 可寻址的 {@link AgentNode}（架构草案 v0.3 §5.2）。
 *
 * <p>26 个域节点是阶段 3b 的交付物，本类是它们**共用的那一层**——
 * 每个域只需提供 {@link DomainCapabilityExecutor}，不必各自重写一遍
 * 「怎么判 NOT_MINE」「怎么组回执」「怎么保证事实是结构化的」。
 * 让 26 个域各写一遍的话，「结构化事实」这条会在某个域上被写成一段自然文本，
 * 而那正是强制信封要防的东西。
 *
 * <p>本类**不做**的事同样要紧:它不解释用户意图（GOAL 的理解归域内规划器）、
 * 不碰中控任务表、不替上游做确认。
 */
public class DomainAgentNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(DomainAgentNode.class);

    private final String agentId;
    private final List<String> ownedDomains;
    private final DomainCapabilityExecutor executor;
    private final boolean autonomous;

    public DomainAgentNode(String agentId, List<String> ownedDomains,
                           DomainCapabilityExecutor executor, boolean autonomous) {
        this.agentId = agentId;
        this.ownedDomains = List.copyOf(ownedDomains);
        this.executor = executor;
        this.autonomous = autonomous;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public boolean autonomous() {
        return autonomous;
    }

    @Override
    public DelegationReceipt handle(DelegationEnvelope envelope) {
        // 先判「是不是我的事」。判错域是入口的问题，回 NOT_MINE 让它改投一次；
        // 若这里含糊地回一个失败，入口判错会被永远计成「域没做完」（§7.1）
        if (!mine(envelope)) {
            log.info("委托不属于本域 delegation={} target={} 本域={}",
                    envelope.delegationId(), agentId, ownedDomains);
            return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION,
                    envelope.delegationId(), DelegationOutcome.NOT_MINE, Map.of(), List.of(),
                    "NOT_MINE", "本域=" + ownedDomains);
        }

        if (envelope.mode() == DelegationMode.GOAL && !autonomous) {
            return DelegationReceipt.fatal(envelope.delegationId(), "GOAL_TO_NON_AUTONOMOUS",
                    "本节点是纯执行器，不接 GOAL");
        }

        DomainCapabilityExecutor.Outcome outcome = executor.execute(envelope);

        // 域侧说办成了却没给结构化事实，在这里就拦住而不是往上传:
        // 传上去之后网关的强制信封会判 FATAL，但那时日志里指向的是「网关拒了回执」,
        // 排障要多绕一层才看到真正的问题在本域实现里
        if (outcome.outcome() == DelegationOutcome.SUCCEEDED && outcome.facts().isEmpty()) {
            log.error("本域声称办成但未给结构化事实 delegation={} capability={}",
                    envelope.delegationId(), envelope.capabilityId());
            return DelegationReceipt.fatal(envelope.delegationId(), "DOMAIN_FACTS_EMPTY",
                    "域实现返回 SUCCEEDED 但事实为空");
        }

        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, envelope.delegationId(),
                outcome.outcome(), outcome.facts(), outcome.missingSlots(),
                outcome.reasonCode(), outcome.diagnostics());
    }

    /**
     * 是否属于本域。
     *
     * <p>两种模式都**先问域侧**（{@link DomainCapabilityExecutor#owns}）:
     * 路由真值是域侧的 {@code supports}，不是能力 ID 的形状。
     *
     * <p>前缀只是域侧没给答案时的兜底。拿前缀当真值是错的,资产里就有反例:
     * {@code cap.transfer} 属于转账域但没有域名段，{@code cap.card.replace} 同时被账户域与
     * 信用卡域承接。按前缀判,这两条能力会被所有域判成「不是我的」,
     * 于是一次完全正确的派单被回成 NOT_MINE，入口改投一次后仍然无人接。
     */
    private boolean mine(DelegationEnvelope envelope) {
        return executor.owns(envelope)
                .orElseGet(() -> matchesDomainPrefix(envelope));
    }

    private boolean matchesDomainPrefix(DelegationEnvelope envelope) {
        if (envelope.mode() != DelegationMode.TASK) {
            return executor.claims(envelope);
        }
        String capability = envelope.capabilityId();
        return capability != null
                && ownedDomains.stream().anyMatch(d -> capability.startsWith("cap." + d + "."));
    }
}
