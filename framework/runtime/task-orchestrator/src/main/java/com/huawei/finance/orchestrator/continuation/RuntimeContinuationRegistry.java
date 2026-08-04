package com.huawei.finance.orchestrator.continuation;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Resolution;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Snapshot;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.SwitchMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class RuntimeContinuationRegistry {
    private final Map<RuntimeType, RuntimeContinuationPort> ports = new EnumMap<>(RuntimeType.class);

    public RuntimeContinuationRegistry(List<RuntimeContinuationPort> values) {
        if (values != null) values.forEach(port -> {
            if (ports.put(port.runtimeType(), port) != null) {
                throw new IllegalStateException("重复 RuntimeContinuationPort: " + port.runtimeType());
            }
        });
    }

    public RuntimeContinuationPort require(RuntimeType type) {
        RuntimeContinuationPort port = ports.get(type);
        if (port == null) throw new IllegalStateException("RUNTIME_CONTINUATION_PORT_MISSING:" + type);
        return port;
    }

    public Snapshot describe(RuntimeType type, String tenantId, String agentId, String runtimeRef) {
        return require(type).describe(tenantId, agentId, runtimeRef);
    }

    public Snapshot resume(RuntimeType type, String tenantId, String agentId, String runtimeRef,
                           Resolution resolution, long expectedStateVersion) {
        return require(type).resume(tenantId, agentId, runtimeRef, resolution, expectedStateVersion);
    }

    public SwitchMode switchMode(RuntimeType type, String tenantId, String agentId, String runtimeRef) {
        return describe(type, tenantId, agentId, runtimeRef).switchMode();
    }
}
