package com.huawei.finance.testkit;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.CapabilityDelegator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Configurable recording delegator shared by orchestrator and Agent tests. */
public final class RecordingCapabilityDelegator implements CapabilityDelegator {

    private final Predicate<String> handles;
    private final BiFunction<UnifiedTask, CapabilityCard, Optional<TaskResult>> response;
    private final CopyOnWriteArrayList<Invocation> invocations = new CopyOnWriteArrayList<>();

    public RecordingCapabilityDelegator(
            Predicate<String> handles,
            BiFunction<UnifiedTask, CapabilityCard, Optional<TaskResult>> response) {
        this.handles = handles;
        this.response = response;
    }

    @Override
    public boolean handles(String capabilityId) {
        return handles.test(capabilityId);
    }

    @Override
    public Optional<TaskResult> delegate(UnifiedTask task, CapabilityCard card) {
        invocations.add(new Invocation(task, card));
        return response.apply(task, card);
    }

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public record Invocation(UnifiedTask task, CapabilityCard card) {
    }
}
