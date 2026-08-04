package com.huawei.finance.a2a;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.obs.AgentLogContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A2A 网关（架构草案 v0.2 §6、v0.3 §6）。
 *
 * <p><b>负责</b>:AgentCard 发现、委托幂等与状态查询、深度环路超时预算、跨 Agent trace。
 *
 * <p><b>不负责</b>（这份清单和上面一样要紧）:解释用户意图、代替目标 Agent 建任务、
 * 执行目标 Agent 的护栏、改目标 Agent 的任务状态、参与跨 Agent 数据库事务。
 * 本类刻意不依赖 {@code task-orchestrator}——依赖它就会顺手把这些都做了。
 *
 * <p>各道闸门的顺序不是随意的:去重在最前，因为重投的委托不该再走一遍任何校验;
 * 环路与深度在执行前，因为它们要防的正是执行引发的下一跳。
 */
public class A2AGateway implements A2ADispatcher {

    private static final Logger log = LoggerFactory.getLogger(A2AGateway.class);

    private final AgentCardRegistry registry;
    private final DelegationStore store;
    private final A2AProperties props;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Tracer tracer;

    public A2AGateway(AgentCardRegistry registry, DelegationStore store, A2AProperties props,
                      MeterRegistry meterRegistry, Clock clock) {
        this(registry, store, props, meterRegistry, clock, null);
    }

    public A2AGateway(AgentCardRegistry registry, DelegationStore store, A2AProperties props,
                      MeterRegistry meterRegistry, Clock clock, Tracer tracer) {
        this.registry = registry;
        this.store = store;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.tracer = tracer;
    }

    /**
     * 投递一次委托。
     *
     * <p>返回的回执一定经过强制信封校验:即使目标节点是个只会说自然语言的 Handler，
     * 它编出来的「已为您转账」也在 {@link DelegationReceipt#requireValidEnvelope} 那里被拦下,
     * 而不是等到对账。
     */
    public DelegationReceipt dispatch(DelegationEnvelope envelope) {
        try (AgentLogContext ignored = AgentLogContext.open(Map.of(
                "traceId", value(envelope.traceId()),
                "taskId", value(envelope.rootTaskId()),
                "delegationId", value(envelope.delegationId()),
                "sourceAgent", value(envelope.sourceAgentId()),
                "targetAgent", value(envelope.targetAgentId())))) {
            Span current = tracer == null ? null : tracer.currentSpan();
            if (current != null && envelope.traceId() != null
                    && !envelope.traceId().equals(current.context().traceId())) {
                log.error("A2A trace 上下文不一致 delegation={} headerTrace={} envelopeTrace={}",
                        envelope.delegationId(), current.context().traceId(), envelope.traceId());
                return DelegationReceipt.fatal(envelope.delegationId(), "TRACE_CONTEXT_MISMATCH",
                        "W3C traceparent 与 A2A 信封 traceId 不一致");
            }

            long started = System.nanoTime();
            Span span = tracer == null ? null : tracer.nextSpan().name("agent.a2a.gateway.route")
                    .tag("a2a.source.agent", envelope.sourceAgentId())
                    .tag("a2a.target.agent", envelope.targetAgentId())
                    .tag("a2a.mode", envelope.mode().name())
                    .tag("a2a.delegation.id", envelope.delegationId()).start();
            DelegationReceipt receipt;
            try (Tracer.SpanInScope spanScope = span == null ? null : tracer.withSpan(span)) {
                receipt = dispatchInternal(envelope);
            }
            MDC.put("outcome", receipt.outcome().name());
            if (receipt.reasonCode() != null) {
                MDC.put("reasonCode", receipt.reasonCode());
            }
            log.info("A2A Gateway 路由完成 target={} outcome={} reason={}",
                    envelope.targetAgentId(), receipt.outcome(), receipt.reasonCode());
            finishSpan(span, started, receipt);
            return receipt;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private DelegationReceipt dispatchInternal(DelegationEnvelope envelope) {
        String id = envelope.delegationId();

        // 1. 入站去重先于一切校验：重投的委托不该再走一遍闸门，
        //    更不该因为「这次的 deadline 已经过了」把首次的成功结果改判成失败
        Optional<DelegationStore.Claim> existing = store.claim(envelope);
        if (existing.isPresent()) {
            return replay(existing.get(), envelope);
        }

        DelegationReceipt gate = checkGates(envelope);
        if (gate != null) {
            store.settle(id, gate);
            return gate;
        }

        AgentCard card = registry.find(envelope.targetAgentId()).orElse(null);
        AgentNode node = registry.node(envelope.targetAgentId()).orElse(null);

        // 三种「办不了」分开报，因为 Owner 不同：
        //   卡都没有  → 附录 F 或域表的问题，要改资产（AGENT_UNKNOWN）
        //   有卡无节点 → 交付进度问题，等那个域交付（DOMAIN_NOT_OPEN）
        // 合并成一句「目标不可用」之后，「这个域还没做」和「这个域码根本不存在」
        // 在监控上是同一条曲线，而后者是配置错误，本该立刻有人去改
        if (card == null) {
            DelegationReceipt unknown = new DelegationReceipt(
                    DelegationEnvelope.CURRENT_VERSION, id, DelegationOutcome.FATAL,
                    Map.of(), java.util.List.of(), "AGENT_UNKNOWN",
                    "目标不在 AgentCard 路由表 target=" + envelope.targetAgentId());
            settleAndCount(id, unknown, envelope);
            return unknown;
        }

        // 注意这里不回 NOT_MINE：NOT_MINE 的含义是「这件事不属于本域」，那是域路由判错。
        // 节点没交付是交付进度问题，回 NOT_MINE 会让入口白花一次改投预算
        if (node == null || !card.deliverable()) {
            DelegationReceipt notOpen = new DelegationReceipt(
                    DelegationEnvelope.CURRENT_VERSION, id, DelegationOutcome.DOMAIN_NOT_OPEN,
                    Map.of(), java.util.List.of(), "DOMAIN_NOT_OPEN",
                    "科技域尚未交付为 AgentNode target=" + envelope.targetAgentId());
            settleAndCount(id, notOpen, envelope);
            return notOpen;
        }

        // 纯执行器收到 GOAL 一律拒绝，而不是尽力猜一个能力去执行（§6.1）
        if (envelope.mode() == DelegationMode.GOAL && !node.autonomous()) {
            DelegationReceipt reject = DelegationReceipt.fatal(id, "GOAL_TO_NON_AUTONOMOUS",
                    "纯执行器不得接 GOAL target=" + envelope.targetAgentId());
            settleAndCount(id, reject, envelope);
            return reject;
        }

        DelegationReceipt receipt = invoke(node, envelope);
        settleAndCount(id, receipt, envelope);
        return receipt;
    }

    private void finishSpan(Span span, long started, DelegationReceipt receipt) {
        meterRegistry.timer(AgentMetrics.A2A_SEGMENT_LATENCY,
                AgentMetrics.TAG_SEGMENT, "gateway",
                AgentMetrics.TAG_OUTCOME, receipt.outcome().name())
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        if (receipt.reasonCode() != null) {
            meterRegistry.counter(AgentMetrics.A2A_FAILURE,
                    AgentMetrics.TAG_SEGMENT, "gateway",
                    AgentMetrics.TAG_REASON, receipt.reasonCode(),
                    AgentMetrics.TAG_OUTCOME, receipt.outcome().name()).increment();
        }
        if (span != null) {
            span.tag("a2a.outcome", receipt.outcome().name());
            if (receipt.reasonCode() != null) {
                span.tag("a2a.reason_code", receipt.reasonCode());
            }
            span.end();
        }
    }

    /**
     * 调用目标节点并强制校验回执。
     *
     * <p>节点抛异常按 FATAL 收口:异常穿透会让委托既没有回执也没有终态，
     * 上游只能靠超时发现,而超时的语义是「结果未知」,比一份 FATAL 回执模糊得多。
     */
    private DelegationReceipt invoke(AgentNode node, DelegationEnvelope envelope) {
        try {
            DelegationReceipt raw = node.handle(envelope);
            return DelegationReceipt.requireValidEnvelope(raw, envelope.delegationId());
        } catch (RuntimeException e) {
            log.error("节点执行抛异常 delegation={} target={} cause={}",
                    envelope.delegationId(), envelope.targetAgentId(), e.toString());
            return DelegationReceipt.fatal(envelope.delegationId(), "NODE_THREW",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 深度、环路、超时三道闸门（§6.3）。过了返回 null。 */
    private DelegationReceipt checkGates(DelegationEnvelope envelope) {
        String id = envelope.delegationId();

        if (!DelegationEnvelope.CURRENT_VERSION.equals(envelope.version())) {
            return DelegationReceipt.fatal(id, "A2A_VERSION_UNSUPPORTED",
                    "只接受 " + DelegationEnvelope.CURRENT_VERSION);
        }

        if (envelope.principal() == null || !envelope.principal().hasSourceSession()) {
            return DelegationReceipt.fatal(id, "PRINCIPAL_CONTEXT_INVALID",
                    "缺少主体上下文或源会话引用");
        }

        // 超限即 FATAL，不静默截断——静默截断要到压测才暴露
        if (envelope.depth() >= props.getMaxDepth()) {
            log.error("委托深度超限 delegation={} 深度={} 上限={} 路径={}",
                    id, envelope.depth(), props.getMaxDepth(), envelope.delegationPath());
            return DelegationReceipt.fatal(id, "DELEGATION_DEPTH_EXCEEDED",
                    "深度=" + envelope.depth() + " 上限=" + props.getMaxDepth());
        }

        // 用 agentId 序列判环而不是「禁止自委托」：同一 Agent 不同能力互相委托是合法的
        if (envelope.wouldLoop()) {
            log.error("委托环路 delegation={} target={} 路径={}",
                    id, envelope.targetAgentId(), envelope.delegationPath());
            return DelegationReceipt.fatal(id, "DELEGATION_LOOP",
                    "路径已含目标 " + envelope.targetAgentId() + " 路径=" + envelope.delegationPath());
        }

        if (envelope.expired(clock.instant())) {
            log.warn("委托在投递前已过期 delegation={} deadline={}", id, envelope.deadline());
            return DelegationReceipt.fatal(id, "DELEGATION_DEADLINE_PASSED",
                    "deadline=" + envelope.deadline());
        }
        return null;
    }

    /**
     * 二次到达:返回首次结果。
     *
     * <p>首次还没落结果时回 {@code PARTIAL} 而不是重跑,也不是等:
     * 重跑会发第二把幂等键，等会把上游的超时预算耗在一个自己已经受理过的委托上。
     * PARTIAL 的含义正是「结果未知」,且上游不得自动重试（§6.2 第 4 条）。
     */
    private DelegationReceipt replay(DelegationStore.Claim claim, DelegationEnvelope envelope) {
        meterRegistry.counter(AgentMetrics.A2A_DELEGATION_REPLAYED,
                AgentMetrics.TAG_AGENT, envelope.targetAgentId()).increment();

        if (claim.settled() && claim.receipt() != null) {
            log.info("委托二次到达，返回首次结果 delegation={} 首次结局={}",
                    claim.delegationId(), claim.receipt().outcome());
            return claim.receipt();
        }
        log.warn("委托二次到达但首次尚未落结果，按结果未知处理 delegation={}", claim.delegationId());
        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, claim.delegationId(),
                DelegationOutcome.PARTIAL, Map.of(), java.util.List.of(),
                "DELEGATION_IN_FLIGHT", "同一委托的首次执行尚未完成，结果未知，不得自动重试");
    }

    private void settleAndCount(String id, DelegationReceipt receipt, DelegationEnvelope envelope) {
        store.settle(id, receipt);
        meterRegistry.counter(AgentMetrics.A2A_DELEGATION,
                AgentMetrics.TAG_AGENT, envelope.targetAgentId(),
                AgentMetrics.TAG_MODE, envelope.mode().name(),
                AgentMetrics.TAG_OUTCOME, receipt.outcome().name()).increment();
    }
}
