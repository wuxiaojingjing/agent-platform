package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.*;
import com.huawei.finance.orchestrator.context.TaskContextStore;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import java.util.ArrayList;
import java.util.List;

/** Builds an ephemeral continuation view. Runtime summaries are never copied into platform rows. */
public class ContinuationContextAssembler {
    private final TaskContextStore store;
    private final RuntimeContinuationRegistry runtimes;

    public ContinuationContextAssembler(TaskContextStore store, RuntimeContinuationRegistry runtimes) {
        this.store = store;
        this.runtimes = runtimes;
    }

    public Context assemble(String tenantId, String agentId, String sessionId) {
        FocusView focus = store.focus(tenantId, agentId, sessionId);
        Snapshot foreground = describe(tenantId, agentId, focus.foreground());
        var suspended = new ArrayList<Snapshot>();
        for (FocusFrame frame : focus.suspended()) {
            Snapshot snapshot = describe(tenantId, agentId, frame);
            if (snapshot != null) suspended.add(snapshot);
        }
        PendingSwitchView pendingSwitch = store.pendingSwitch(tenantId, agentId, sessionId)
                .map(value -> new PendingSwitchView(value.switchId(), value.version(),
                        List.of(Event.SWITCH_ACCEPT, Event.SWITCH_REJECT)))
                .orElse(null);
        return new Context(foreground, suspended, pendingSwitch);
    }

    private Snapshot describe(String tenantId, String agentId, FocusFrame frame) {
        if (frame == null || frame.subjectType() != FocusSubjectType.PLATFORM_TASK) return null;
        PlatformTask task = store.task(tenantId, agentId, frame.subjectRef()).orElse(null);
        if (task == null || task.bindingState() != BindingState.BOUND || task.runtimeRef() == null) return null;
        return runtimes.describe(task.runtimeType(), tenantId, agentId, task.runtimeRef());
    }
}
