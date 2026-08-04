package com.huawei.finance.runtime.spi;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.common.context.RuntimeModuleStep;
import com.huawei.finance.intent.PathSummary;
import java.util.List;

/** 观测用决策留痕（控制台 recent 等）；缺省可为空实现。 */
@FunctionalInterface
public interface DecisionRecorder {

    void record(String traceId, String sessionId, String query, RouteDecision decision, String taskId,
                String usedTemplate, boolean fellBack, List<String> degradedChannels,
                long latencyMs, PathSummary path, List<String> gatewayCalls,
                List<RuntimeModuleStep> moduleSteps);

    DecisionRecorder NOOP = (traceId, sessionId, query, decision, taskId, usedTemplate, fellBack,
                             degraded, latencyMs, path, gatewayCalls, moduleSteps) -> {
    };
}
