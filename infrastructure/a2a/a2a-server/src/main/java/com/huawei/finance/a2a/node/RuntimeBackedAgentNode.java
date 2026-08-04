package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.a2a.PrincipalResolver;
import com.huawei.finance.contracts.a2a.ResolvedPrincipal;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.runtime.invocation.AgentInvocationOutcome;
import com.huawei.finance.runtime.invocation.AgentInvocationRequest;
import com.huawei.finance.runtime.invocation.AgentInvocationRuntime;
import com.huawei.finance.runtime.invocation.TargetSessionKeys;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 生产 Extension Host 的唯一 A2A 节点：所有入站都经过目标 Agent Runtime。 */
public final class RuntimeBackedAgentNode implements AgentNode {

    private final String agentId;
    private final AgentInvocationRuntime runtime;
    private final PrincipalResolver principals;

    public RuntimeBackedAgentNode(
            String agentId, AgentInvocationRuntime runtime, PrincipalResolver principals) {
        this.agentId = agentId;
        this.runtime = runtime;
        this.principals = principals;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public DelegationReceipt handle(DelegationEnvelope envelope) {
        DelegationReceipt invalid = validate(envelope);
        if (invalid != null) {
            return invalid;
        }
        ResolvedPrincipal principal;
        try {
            principal = principals.resolve(envelope.tenantId(), agentId, envelope.principal());
        } catch (RuntimeException e) {
            return DelegationReceipt.fatal(envelope.delegationId(), "PRINCIPAL_RESOLUTION_FAILED",
                    e.getClass().getSimpleName());
        }
        if (principal == null) {
            return DelegationReceipt.fatal(envelope.delegationId(), "PRINCIPAL_RESOLUTION_FAILED",
                    "主体解析器返回空结果");
        }

        String targetSession = TargetSessionKeys.of(envelope.sourceAgentId(),
                envelope.principal().sourceSessionRef(), envelope.rootTaskId());
        AgentInvocationOutcome outcome = runtime.invoke(new AgentInvocationRequest(
                envelope.tenantId(), envelope.sourceAgentId(), envelope.targetAgentId(),
                targetSession, envelope.rootTaskId(), envelope.parentTaskId(),
                envelope.sourceTaskId(), envelope.delegationPath(), envelope.traceId(),
                envelope.mode(), principal,
                envelope.goal(), envelope.capabilityId(), envelope.parameters(),
                envelope.confirmedFacts(), envelope.deadline(), envelope.intentPath(),
                Enums.InvocationOrigin.A2A, envelope.delegationId(), envelope.subtaskContext()));
        return receipt(envelope.delegationId(), outcome, envelope.intentPath(), principal.verified());
    }

    private DelegationReceipt validate(DelegationEnvelope envelope) {
        if (!DelegationEnvelope.CURRENT_VERSION.equals(envelope.version())) {
            return DelegationReceipt.fatal(envelope.delegationId(), "A2A_VERSION_UNSUPPORTED",
                    "只接受 " + DelegationEnvelope.CURRENT_VERSION);
        }
        if (!agentId.equals(envelope.targetAgentId())) {
            return DelegationReceipt.fatal(envelope.delegationId(), "A2A_TARGET_MISMATCH",
                    "信封目标与本进程身份不一致");
        }
        if (envelope.principal() == null || !envelope.principal().hasSourceSession()) {
            return DelegationReceipt.fatal(envelope.delegationId(), "PRINCIPAL_CONTEXT_INVALID",
                    "缺少主体上下文或源会话引用");
        }
        if (envelope.expired(Instant.now())) {
            return DelegationReceipt.fatal(envelope.delegationId(), "DELEGATION_DEADLINE_PASSED",
                    "委托已过期");
        }
        if (envelope.subtaskContext() != null
                && envelope.subtaskContext().expired(Instant.now())) {
            return DelegationReceipt.fatal(envelope.delegationId(), "CONTEXT_LEASE_EXPIRED",
                    "子任务上下文已过期");
        }
        if (envelope.wouldLoop()) {
            return DelegationReceipt.fatal(envelope.delegationId(), "DELEGATION_LOOP", "委托路径成环");
        }
        return null;
    }

    private static DelegationReceipt receipt(
            String delegationId, AgentInvocationOutcome outcome, Enums.TaskSource inheritedPath,
            boolean principalVerified) {
        if ("NOT_MINE".equals(outcome.reasonCode())) {
            return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, delegationId,
                    DelegationOutcome.NOT_MINE, Map.of(), List.of(), "NOT_MINE", null);
        }
        if (outcome.result() == null) {
            DelegationOutcome state = outcome.missingSlots().isEmpty()
                    ? DelegationOutcome.FATAL : DelegationOutcome.NEED_USER;
            return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, delegationId,
                    state, Map.of(), missing(outcome.missingSlots()), outcome.reasonCode(), null,
                    outcome.contextDelta());
        }
        DelegationOutcome translated = switch (outcome.result().status()) {
            case SUCCESS -> DelegationOutcome.SUCCEEDED;
            case NEED_USER -> DelegationOutcome.NEED_USER;
            case PARTIAL -> DelegationOutcome.PARTIAL;
            case FAILED, CANCELLED -> outcome.result().failureClass() == Enums.FailureClass.RETRYABLE
                    ? DelegationOutcome.PARTIAL : DelegationOutcome.FATAL;
        };
        Map<String, Object> facts = new java.util.LinkedHashMap<>(outcome.facts());
        if (outcome.taskId() != null) facts.put("targetTaskId", outcome.taskId());
        Enums.TaskSource path = outcome.intentPath() == null ? inheritedPath : outcome.intentPath();
        if (path != null) facts.put("intentPath", path.name());
        facts.put("invocationOrigin", Enums.InvocationOrigin.A2A.name());
        facts.put("principalVerified", principalVerified);
        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, delegationId,
                translated, facts, missing(outcome.missingSlots()),
                outcome.reasonCode(), null, outcome.contextDelta());
    }

    private static List<DelegationReceipt.MissingSlot> missing(List<String> slots) {
        return slots.stream().map(slot ->
                new DelegationReceipt.MissingSlot(slot, List.of(), "MISSING")).toList();
    }
}
