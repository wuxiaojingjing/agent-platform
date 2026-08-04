package com.huawei.finance.orchestrator.loop;

import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.stability.Spi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

@Spi
public interface AgentLoopRepository {
    Run open(StartRequest request);
    Optional<Run> find(String tenantId, String agentId, String loopId);
    List<Step> steps(String tenantId, String agentId, String loopId);
    default Optional<String> reasonCode(String tenantId, String agentId, String loopId) {
        return Optional.empty();
    }
    boolean propose(String tenantId, String agentId, String loopId, long expectedVersion, Action action);
    boolean claim(String tenantId, String agentId, String loopId, int stepIndex, long expectedVersion);
    boolean recoverClaimed(String tenantId, String agentId, String loopId, int stepIndex,
                           long expectedVersion, Instant claimedBefore, String reasonCode);
    boolean waitForInput(String tenantId, String agentId, String loopId, long expectedVersion,
                         List<String> pendingSlots, String reasonCode);
    Run resume(String tenantId, String agentId, String loopId, long expectedVersion,
               Status waitingStatus, Map<String,Object> slotUpdates);
    Run complete(String tenantId, String agentId, String loopId, int stepIndex,
                 long expectedVersion, Observation observation, Status nextStatus);
    boolean transition(String tenantId, String agentId, String loopId, long expectedVersion,
                       Status from, Status to, String reasonCode);
}
