package com.huawei.finance.a2a;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.a2a.PrincipalContext;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入口侧委托客户端：组信封、投递、按 §7.1 处理投错域。
 *
 * <p>为什么改投只有一次（{@link A2AProperties#getMaxReroutes()} 默认 1）:
 * 遍历 Top-K 等于让一次判错的代价变成 K 次委托的延迟与成本，
 * 而第二次还错就说明域路由本身有问题——该走澄清问用户，不该继续猜。
 *
 * <p>改投用的是**新** {@code delegationId}:同一个 id 投到第二个域，
 * 网关的入站去重会把它当成重投，直接返回第一个域的 {@code NOT_MINE}。
 */
public class DelegationClient {

    private static final Logger log = LoggerFactory.getLogger(DelegationClient.class);

    private final A2ADispatcher gateway;
    private final A2AProperties props;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public DelegationClient(A2ADispatcher gateway, A2AProperties props,
                            MeterRegistry meterRegistry, Clock clock) {
        this.gateway = gateway;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * 按域路由候选投递，投错域时改投一次。
     *
     * @param candidates 域路由 Top-K 的 agentId，按分数降序
     * @return 最终回执。候选耗尽仍 NOT_MINE 时返回最后一份——
     *         调用方据此走澄清，而不是自己再猜一个域
     */
    public DelegationReceipt delegate(DelegationRequest request, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return DelegationReceipt.fatal(UUID.randomUUID().toString(), "NO_ROUTE_CANDIDATE",
                    "域路由没给出任何候选");
        }

        int attempts = Math.min(candidates.size(), props.getMaxReroutes() + 1);
        DelegationReceipt last = null;

        for (int i = 0; i < attempts; i++) {
            String target = candidates.get(i);
            DelegationEnvelope envelope = envelopeFor(request, target);
            last = gateway.dispatch(envelope);

            if (last.outcome() != DelegationOutcome.NOT_MINE) {
                return last;
            }

            // NOT_MINE 才改投。DOMAIN_NOT_OPEN 不改投——那是交付进度问题，
            // 换个域投不会让这个域变成已建成，只会把一次明确失败拖成两次
            if (i + 1 < attempts) {
                meterRegistry.counter(AgentMetrics.A2A_REROUTED,
                        "from", target, "to", candidates.get(i + 1)).increment();
                log.info("投错域，改投 trace={} 原目标={} 新目标={}",
                        request.traceId(), target, candidates.get(i + 1));
            }
        }

        log.warn("改投已用尽仍未落域 trace={} 候选={} 上限改投={}",
                request.traceId(), candidates, props.getMaxReroutes());
        return last;
    }

    private DelegationEnvelope envelopeFor(DelegationRequest request, String target) {
        List<String> path = new ArrayList<>(request.delegationPath());
        path.add(request.sourceAgentId());

        return new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, request.tenantId(), request.sourceAgentId(),
                target, request.rootTaskId(), request.parentTaskId(), request.sourceTaskId(),
                // 每次投递一把新 id：改投复用旧 id 会被入站去重当成重投
                UUID.randomUUID().toString(), request.traceId(), request.principal(), request.mode(),
                request.intentPath(), request.goal(), request.capabilityId(), request.parameters(),
                request.confirmedFacts(),
                DeadlineBudget.next(request.upstreamDeadline(), clock.instant(),
                        props.getLocalTimeoutMs(), request.cardTimeoutMs(),
                        props.getReturnReserveMs()),
                path, request.subtaskContext());
    }

    /**
     * 一次委托的入口侧输入。
     *
     * <p>与 {@link DelegationEnvelope} 分开，是因为信封里的 {@code delegationId}、
     * {@code deadline}、{@code delegationPath} 由本类计算，不该由调用方填——
     * 让调用方填 deadline 就等于让它绕过逐层收缩。
     */
    public record DelegationRequest(
            String tenantId,
            String sourceAgentId,
            String rootTaskId,
            String parentTaskId,
            String sourceTaskId,
            String traceId,
            DelegationMode mode,
            Enums.TaskSource intentPath,
            PrincipalContext principal,
            String goal,
            String capabilityId,
            Map<String, Object> parameters,
            List<Map<String, Object>> confirmedFacts,
            java.time.Instant upstreamDeadline,
            long cardTimeoutMs,
            List<String> delegationPath,
            SubtaskContextEnvelope subtaskContext) {

        public DelegationRequest {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
            delegationPath = delegationPath == null ? List.of() : List.copyOf(delegationPath);
            if (mode == DelegationMode.TASK && intentPath == null) {
                throw new IllegalArgumentException("TASK 委托必须携带意图识别路径");
            }
        }

        public DelegationRequest(
                String tenantId, String sourceAgentId, String rootTaskId, String parentTaskId,
                String sourceTaskId, String traceId, DelegationMode mode,
                Enums.TaskSource intentPath, PrincipalContext principal, String goal,
                String capabilityId, Map<String, Object> parameters,
                List<Map<String, Object>> confirmedFacts, java.time.Instant upstreamDeadline,
                long cardTimeoutMs, List<String> delegationPath) {
            this(tenantId, sourceAgentId, rootTaskId, parentTaskId, sourceTaskId, traceId,
                    mode, intentPath, principal, goal, capabilityId, parameters, confirmedFacts,
                    upstreamDeadline, cardTimeoutMs, delegationPath, null);
        }

        public DelegationRequest(
                String tenantId, String sourceAgentId, String rootTaskId, String parentTaskId,
                String sourceTaskId, String traceId, DelegationMode mode,
                PrincipalContext principal, String goal, String capabilityId,
                Map<String, Object> parameters, List<Map<String, Object>> confirmedFacts,
                java.time.Instant upstreamDeadline, long cardTimeoutMs,
                List<String> delegationPath) {
            this(tenantId, sourceAgentId, rootTaskId, parentTaskId, sourceTaskId, traceId,
                    mode, mode == DelegationMode.TASK ? Enums.TaskSource.FAST_PATH : null,
                    principal, goal, capabilityId, parameters, confirmedFacts, upstreamDeadline,
                    cardTimeoutMs, delegationPath, null);
        }

        /** 源码迁移辅助；生产调用方必须使用带主体上下文的构造器。 */
        public DelegationRequest(
                String tenantId, String sourceAgentId, String rootTaskId, String parentTaskId,
                String sourceTaskId, String traceId, DelegationMode mode, String goal,
                String capabilityId, Map<String, Object> parameters,
                List<Map<String, Object>> confirmedFacts, java.time.Instant upstreamDeadline,
                long cardTimeoutMs, List<String> delegationPath) {
            this(tenantId, sourceAgentId, rootTaskId, parentTaskId, sourceTaskId, traceId,
                    mode, mode == DelegationMode.TASK ? Enums.TaskSource.FAST_PATH : null,
                    PrincipalContext.anonymous("TEST", "legacy:" + rootTaskId),
                    goal, capabilityId, parameters, confirmedFacts, upstreamDeadline,
                    cardTimeoutMs, delegationPath, null);
        }
    }
}
