package com.huawei.finance.orchestrator.continuation;

import static com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeContinuationRegistryTest {
    @Test
    void delegatesDescribeResumeAndSwitchPermissionToRegisteredRuntime() {
        RecordingPort port = new RecordingPort();
        RuntimeContinuationRegistry registry = new RuntimeContinuationRegistry(List.of(port));
        Resolution resolution = new Resolution(Event.CONFIRM, "loop-1", Map.of(), null, 1, "TEST");

        assertThat(registry.describe(RuntimeType.AGENT_LOOP, "tenant", "agent", "loop-1").stateVersion())
                .isEqualTo(7);
        assertThat(registry.switchMode(RuntimeType.AGENT_LOOP, "tenant", "agent", "loop-1"))
                .isEqualTo(SwitchMode.DENY_SWITCH);
        assertThat(registry.resume(RuntimeType.AGENT_LOOP, "tenant", "agent", "loop-1", resolution, 7)
                .stateVersion()).isEqualTo(8);
        assertThat(port.lastResolution).isSameAs(resolution);
    }

    @Test
    void rejectsDuplicateAndMissingRuntimePorts() {
        RecordingPort port = new RecordingPort();
        assertThatThrownBy(() -> new RuntimeContinuationRegistry(List.of(port, port)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重复");
        RuntimeContinuationRegistry empty = new RuntimeContinuationRegistry(List.of());
        assertThatThrownBy(() -> empty.describe(RuntimeType.AGENT_LOOP, "tenant", "agent", "loop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUNTIME_CONTINUATION_PORT_MISSING:AGENT_LOOP");
    }

    private static final class RecordingPort implements RuntimeContinuationPort {
        private Resolution lastResolution;
        @Override public RuntimeType runtimeType() { return RuntimeType.AGENT_LOOP; }
        @Override public Snapshot describe(String tenant, String agent, String ref) {
            return snapshot(ref, 7);
        }
        @Override public Snapshot resume(String tenant, String agent, String ref,
                                         Resolution resolution, long version) {
            lastResolution = resolution;
            return snapshot(ref, version + 1);
        }
        private static Snapshot snapshot(String ref, long version) {
            return new Snapshot(RuntimeType.AGENT_LOOP, ref, "RUNNING", null,
                    List.of(Event.CONTINUE_CURRENT), Map.of(), "Loop", version, SwitchMode.DENY_SWITCH);
        }
    }
}
