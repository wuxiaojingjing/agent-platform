package com.huawei.finance.contracts.a2a;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A2A 委托信封（架构草案 v0.2 §6.2 字段清单）。
 *
 * <p>{@code delegationId} 是下游唯一的去重依据。不定这一条的后果很具体:上游重投一次委托，
 * 下游走两遍完整流程、发两把各自合法的本地幂等键，两笔转账都「合规」，两侧日志都正常。
 *
 * <p>{@code delegationPath} 是 agentId 序列而不是「禁止自委托」的布尔量:
 * 同一 Agent 不同能力互相委托是合法的，要拒的是路径上出现重复 agentId 的环。
 *
 * <p>{@code deadline} 是**绝对时刻**且逐层只能缩小。若每层各取本地上限，
 * 总时长是各层之和，用户端就是一次没有反馈的久等。
 */
@Api
public record DelegationEnvelope(
        String version,
        String tenantId,
        String sourceAgentId,
        String targetAgentId,
        String rootTaskId,
        String parentTaskId,
        String sourceTaskId,
        String delegationId,
        String traceId,
        PrincipalContext principal,
        DelegationMode mode,
        Enums.TaskSource intentPath,
        String goal,
        String capabilityId,
        Map<String, Object> parameters,
        List<Map<String, Object>> confirmedFacts,
        Instant deadline,
        List<String> delegationPath,
        SubtaskContextEnvelope subtaskContext) {

    /** 当前信封版本。版本不符的回执整单 FATAL，见 {@link DelegationReceipt}。 */
    public static final String CURRENT_VERSION = "a2a/2";

    public DelegationEnvelope {
        Objects.requireNonNull(delegationId, "delegationId 是下游唯一去重依据，不能为空");
        Objects.requireNonNull(targetAgentId, "targetAgentId 不能为空");
        Objects.requireNonNull(mode, "mode 不能为空");
        version = version == null ? CURRENT_VERSION : version;
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        delegationPath = delegationPath == null ? List.of() : List.copyOf(delegationPath);
        if (mode == DelegationMode.TASK && intentPath == null) {
            throw new IllegalArgumentException("TASK 委托必须携带已完成的意图识别路径");
        }

        // GOAL 必须有目标，TASK 必须有能力 ID。两者都缺时下游只能靠猜，
        // 而「靠猜」在 GOAL 上就是那句「已为您转账 1000 元」的来源
        if (mode == DelegationMode.GOAL && (goal == null || goal.isBlank())) {
            throw new IllegalArgumentException("GOAL 委托必须带 goal");
        }
        if (mode == DelegationMode.TASK && (capabilityId == null || capabilityId.isBlank())) {
            throw new IllegalArgumentException("TASK 委托必须带 capabilityId");
        }
        if (subtaskContext != null && mode == DelegationMode.TASK
                && !subtaskContext.allowsCapability(capabilityId)) {
            throw new IllegalArgumentException("子任务上下文未授权能力 " + capabilityId);
        }
    }

    public DelegationEnvelope(
            String version, String tenantId, String sourceAgentId, String targetAgentId,
            String rootTaskId, String parentTaskId, String sourceTaskId, String delegationId,
            String traceId, PrincipalContext principal, DelegationMode mode,
            Enums.TaskSource intentPath, String goal, String capabilityId,
            Map<String, Object> parameters, List<Map<String, Object>> confirmedFacts,
            Instant deadline, List<String> delegationPath) {
        this(version, tenantId, sourceAgentId, targetAgentId, rootTaskId, parentTaskId,
                sourceTaskId, delegationId, traceId, principal, mode, intentPath, goal,
                capabilityId, parameters, confirmedFacts, deadline, delegationPath, null);
    }

    public DelegationEnvelope(
            String version, String tenantId, String sourceAgentId, String targetAgentId,
            String rootTaskId, String parentTaskId, String sourceTaskId, String delegationId,
            String traceId, PrincipalContext principal, DelegationMode mode, String goal,
            String capabilityId, Map<String, Object> parameters,
            List<Map<String, Object>> confirmedFacts, Instant deadline,
            List<String> delegationPath) {
        this(version, tenantId, sourceAgentId, targetAgentId, rootTaskId, parentTaskId,
                sourceTaskId, delegationId, traceId, principal, mode,
                mode == DelegationMode.TASK ? Enums.TaskSource.FAST_PATH : null,
                goal, capabilityId, parameters, confirmedFacts, deadline, delegationPath, null);
    }

    /** 源码迁移辅助；线上 JSON 契约不兼容 v1。 */
    public DelegationEnvelope(
            String version, String tenantId, String sourceAgentId, String targetAgentId,
            String rootTaskId, String parentTaskId, String sourceTaskId, String delegationId,
            String traceId, DelegationMode mode, String goal, String capabilityId,
            Map<String, Object> parameters, List<Map<String, Object>> confirmedFacts,
            Instant deadline, List<String> delegationPath) {
        this(version, tenantId, sourceAgentId, targetAgentId, rootTaskId, parentTaskId,
                sourceTaskId, delegationId, traceId,
                PrincipalContext.anonymous("TEST", "legacy:" + sessionSeed(rootTaskId, sourceTaskId)),
                mode, mode == DelegationMode.TASK ? Enums.TaskSource.FAST_PATH : null,
                goal, capabilityId,
                parameters, confirmedFacts, deadline, delegationPath, null);
    }

    private static String sessionSeed(String rootTaskId, String sourceTaskId) {
        return rootTaskId != null ? rootTaskId : (sourceTaskId != null ? sourceTaskId : "unknown");
    }

    /** 委托深度 = 路径长度。根委托路径只含入口自己。 */
    public int depth() {
        return delegationPath.size();
    }

    /** 路径上是否已出现过目标 Agent——出现即环路（§6.3）。 */
    public boolean wouldLoop() {
        return delegationPath.contains(targetAgentId);
    }

    /** 是否已过期。绝对时刻判定，不依赖各层本地时钟差之外的约定。 */
    public boolean expired(Instant now) {
        return deadline != null && now.isAfter(deadline);
    }
}
