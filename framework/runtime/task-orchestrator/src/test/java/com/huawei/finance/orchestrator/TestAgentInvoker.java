package com.huawei.finance.orchestrator;

import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.contracts.port.DomainAgent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.Executors;

/** 测试用的 AgentInvoker 装配，超时上限取默认值。 */
final class TestAgentInvoker {

    private TestAgentInvoker() {
    }

    static AgentInvoker of(DomainAgent... agents) {
        return of(new OrchestratorProperties(), agents);
    }

    static AgentInvoker of(OrchestratorProperties props, DomainAgent... agents) {
        return new AgentInvoker(List.of(agents), pool(), props, new SimpleMeterRegistry(), null);
    }

    /** 装了委托通道的装配。委托与本地 Agent 在同一个位置上二选一。 */
    static AgentInvoker of(CapabilityDelegator delegator, DomainAgent... agents) {
        return new AgentInvoker(List.of(agents), pool(), new OrchestratorProperties(),
                new SimpleMeterRegistry(), delegator);
    }

    private static java.util.concurrent.ExecutorService pool() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "test-agent");
            t.setDaemon(true);
            return t;
        });
    }
}
