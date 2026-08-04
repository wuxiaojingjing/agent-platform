package com.huawei.finance.runtime.context;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.context.ContextCompilation;
import com.huawei.finance.context.ContextLeaseCompiler;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts;
import com.huawei.finance.orchestrator.task.TaskRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds all current-turn projections from one focus/runtime/history read boundary. */
public final class TurnContextAssembler {
    private final ContextLeaseCompiler leases;

    public TurnContextAssembler(ContextLeaseCompiler leases) {
        this.leases = leases;
    }

    public TurnContextSnapshot assemble(RequestContext request, String currentQuery,
                                        Optional<TaskRecord> activeTask,
                                        SubtaskContextEnvelope delegated,
                                        Optional<ContinuationContracts.Context> continuation) {
        ContinuationContracts.Snapshot runtime = continuation
                .map(ContinuationContracts.Context::foreground).orElse(null);
        String goal = firstNonBlank(runtime == null ? null : runtime.goal(),
                activeTask.map(TaskRecord::goal).orElse(null), currentQuery);

        Map<String, Object> confirmed = new LinkedHashMap<>();
        activeTask.ifPresent(task -> confirmed.putAll(task.parameters()));
        if (runtime != null) confirmed.putAll(runtime.confirmedFacts());
        if (delegated != null) {
            if (delegated.expired(java.time.Instant.now())) {
                java.time.Instant expiresAt = java.time.Instant.now().plus(leases.leaseTtl());
                return new TurnContextSnapshot(continuation,
                        new ContextCompilation(ContextLease.degraded(request.sessionId(), goal, expiresAt),
                                IntentContext.degraded(request.sessionId(), goal, expiresAt)));
            }
            confirmed.putAll(delegated.confirmedInputs());
            delegated.facts().forEach(fact -> confirmed.putAll(fact.value()));
        }

        List<ContextEvidence> additions = new ArrayList<>();
        if (runtime != null) additions.add(runtimeEvidence(runtime));
        if (delegated != null) additions.addAll(delegated.facts());
        List<ContextLease.PendingItem> pending = pendingItems(runtime, activeTask);
        ContextCompilation compiled = leases.compileContext(request.spaceId(), request.agentId(),
                request.sessionId(), goal, confirmed, pending, additions);
        return new TurnContextSnapshot(continuation, compiled);
    }

    private static ContextEvidence runtimeEvidence(ContinuationContracts.Snapshot runtime) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("runtimeType", runtime.runtimeType().name());
        value.put("runtimeRef", runtime.runtimeRef());
        value.put("runtimeState", runtime.runtimeState());
        value.put("runtimeStateVersion", runtime.stateVersion());
        value.put("goal", runtime.goal());
        value.put("confirmedFacts", runtime.confirmedFacts());
        value.put("runtimeFacts", runtime.runtimeFacts());
        if (runtime.pendingInteraction() != null) {
            value.put("pendingInteraction", runtime.pendingInteraction());
        }
        return new ContextEvidence("runtime:" + runtime.runtimeType().name().toLowerCase()
                + ':' + runtime.runtimeRef(), ContextEvidence.Kind.RUNTIME_STATE, Map.copyOf(value),
                null, runtime.runtimeRef(), null, java.time.Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
    }

    private static List<ContextLease.PendingItem> pendingItems(
            ContinuationContracts.Snapshot runtime, Optional<TaskRecord> activeTask) {
        if (runtime != null && runtime.pendingInteraction() != null) {
            ContinuationContracts.PendingInteraction item = runtime.pendingInteraction();
            Enums.PendingAction action = switch (item.type()) {
                case "CONFIRM" -> Enums.PendingAction.CONFIRM;
                case "REVIEW" -> Enums.PendingAction.REVIEW;
                default -> item.expectedAnswers().isEmpty()
                        ? Enums.PendingAction.FILLING_SLOT : Enums.PendingAction.SELECT;
            };
            return List.of(new ContextLease.PendingItem(item.expectedSlot(), action,
                    item.expectedAnswers()));
        }
        return activeTask.<List<ContextLease.PendingItem>>map(task -> switch (task.state()) {
            case CONFIRM_PENDING -> List.of(new ContextLease.PendingItem(
                    task.pendingSlot(), Enums.PendingAction.CONFIRM, List.of()));
            case REVIEW_PENDING -> List.of(new ContextLease.PendingItem(
                    task.pendingSlot(), Enums.PendingAction.REVIEW, List.of()));
            case CLARIFY_PENDING -> List.of(new ContextLease.PendingItem(task.pendingSlot(),
                    task.expectedAnswers().isEmpty() ? Enums.PendingAction.FILLING_SLOT
                            : Enums.PendingAction.SELECT, task.expectedAnswers()));
            default -> List.of();
        }).orElse(List.of());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
