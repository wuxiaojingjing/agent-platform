package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * 注册中心 → 快慢召回（v0.7 附录 B {@code CapabilityCard}，两层粒度见 §3.6.2）。
 *
 * <p>{@code type=AGENT} 时 {@code parentCapabilityId} 为空；{@code TOOL/SKILL} 必须填写所属
 * Agent 的 {@code capabilityId}。
 *
 * <p>{@code utterances} 与 {@code keywords} 为本工程补充的检索侧字段：能力卡要参与 BM25 与
 * 向量召回，必须携带可检索文本。它们属于召回视图而非能力语义，发布时与卡同版本。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record CapabilityCard(
        String capabilityId,
        String name,
        Enums.CapabilityType type,
        Enums.Granularity granularity,
        String parentCapabilityId,
        List<String> domains,
        String description,
        List<String> supportedIntents,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<String> preconditions,
        List<String> sideEffects,
        RiskLevel riskLevel,
        int timeoutMs,
        Enums.Idempotency idempotency,
        String owner,
        String version,
        Enums.CapabilityStatus status,
        List<String> utterances,
        List<String> keywords,
        List<String> requiredSlots,
        Enums.GuardrailOwner guardrailOwner,
        Boolean principalRequired,
        ConfirmationPolicy confirmationPolicy,
        LoopAccess loopAccess,
        Boolean entryVisible,
        Enums.ImplementationStatus implementationStatus,
        List<String> positiveBoundary,
        List<String> negativeBoundary,
        List<String> fallbackCapabilityIds) {

    public CapabilityCard {
        domains = domains == null ? List.of() : List.copyOf(domains);
        supportedIntents = supportedIntents == null ? List.of() : List.copyOf(supportedIntents);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        preconditions = preconditions == null ? List.of() : List.copyOf(preconditions);
        sideEffects = sideEffects == null ? List.of() : List.copyOf(sideEffects);
        utterances = utterances == null ? List.of() : List.copyOf(utterances);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        riskLevel = riskLevel == null ? RiskLevel.R0 : riskLevel;
        status = status == null ? Enums.CapabilityStatus.ACTIVE : status;
        // 缺省归领域方。理由见 Enums.GuardrailOwner：默认成 MAIN 会让每张没表态的卡
        // 都变成「主控声称管了护栏」，而主控手上并没有那个领域的限额与适当性口径
        guardrailOwner = guardrailOwner == null ? Enums.GuardrailOwner.DOMAIN : guardrailOwner;
        principalRequired = principalRequired == null ? Boolean.TRUE : principalRequired;
        confirmationPolicy = confirmationPolicy == null
                ? (riskLevel == RiskLevel.R0 ? ConfirmationPolicy.NONE : ConfirmationPolicy.EXPLICIT)
                : confirmationPolicy;
        if (riskLevel == RiskLevel.R2 && confirmationPolicy != ConfirmationPolicy.EXPLICIT) {
            throw new IllegalArgumentException("R2 能力必须使用 EXPLICIT confirmationPolicy");
        }
        if (riskLevel == RiskLevel.R1 && confirmationPolicy == ConfirmationPolicy.NONE) {
            throw new IllegalArgumentException("R1 能力不得使用 NONE confirmationPolicy");
        }
        loopAccess = loopAccess == null ? LoopAccess.DEFAULT : loopAccess;
        entryVisible = entryVisible == null ? Boolean.TRUE : entryVisible;
        implementationStatus = implementationStatus == null
                ? (type == Enums.CapabilityType.AGENT && status == Enums.CapabilityStatus.DISABLED
                        ? Enums.ImplementationStatus.SCAFFOLD : Enums.ImplementationStatus.IMPLEMENTED)
                : implementationStatus;
        positiveBoundary = positiveBoundary == null ? List.of() : List.copyOf(positiveBoundary);
        negativeBoundary = negativeBoundary == null ? List.of() : List.copyOf(negativeBoundary);
        fallbackCapabilityIds = fallbackCapabilityIds == null ? List.of() : List.copyOf(fallbackCapabilityIds);
        if (type == Enums.CapabilityType.AGENT
                && implementationStatus == Enums.ImplementationStatus.SCAFFOLD
                && status != Enums.CapabilityStatus.DISABLED) {
            throw new IllegalArgumentException("SCAFFOLD Agent 必须使用 DISABLED status");
        }
    }

    /** Source-compatible constructor for assets created before Agent boundary metadata. */
    public CapabilityCard(
            String capabilityId, String name, Enums.CapabilityType type, Enums.Granularity granularity,
            String parentCapabilityId, List<String> domains, String description,
            List<String> supportedIntents, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, List<String> preconditions, List<String> sideEffects,
            RiskLevel riskLevel, int timeoutMs, Enums.Idempotency idempotency, String owner,
            String version, Enums.CapabilityStatus status, List<String> utterances,
            List<String> keywords, List<String> requiredSlots, Enums.GuardrailOwner guardrailOwner,
            Boolean principalRequired, ConfirmationPolicy confirmationPolicy, LoopAccess loopAccess,
            Boolean entryVisible) {
        this(capabilityId, name, type, granularity, parentCapabilityId, domains, description,
                supportedIntents, inputSchema, outputSchema, preconditions, sideEffects, riskLevel,
                timeoutMs, idempotency, owner, version, status, utterances, keywords, requiredSlots,
                guardrailOwner, principalRequired, confirmationPolicy, loopAccess, entryVisible,
                null, List.of(), List.of(), List.of());
    }

    /** Source-compatible constructor for cards created before internal support capabilities existed. */
    public CapabilityCard(
            String capabilityId, String name, Enums.CapabilityType type, Enums.Granularity granularity,
            String parentCapabilityId, List<String> domains, String description,
            List<String> supportedIntents, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, List<String> preconditions, List<String> sideEffects,
            RiskLevel riskLevel, int timeoutMs, Enums.Idempotency idempotency, String owner,
            String version, Enums.CapabilityStatus status, List<String> utterances,
            List<String> keywords, List<String> requiredSlots, Enums.GuardrailOwner guardrailOwner,
            Boolean principalRequired, ConfirmationPolicy confirmationPolicy, LoopAccess loopAccess) {
        this(capabilityId, name, type, granularity, parentCapabilityId, domains, description,
                supportedIntents, inputSchema, outputSchema, preconditions, sideEffects, riskLevel,
                timeoutMs, idempotency, owner, version, status, utterances, keywords, requiredSlots,
                guardrailOwner, principalRequired, confirmationPolicy, loopAccess, Boolean.TRUE,
                null, List.of(), List.of(), List.of());
    }

    /** 源码迁移辅助；旧卡未声明时按需要已验证主体处理。 */
    public CapabilityCard(
            String capabilityId, String name, Enums.CapabilityType type, Enums.Granularity granularity,
            String parentCapabilityId, List<String> domains, String description,
            List<String> supportedIntents, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, List<String> preconditions, List<String> sideEffects,
            RiskLevel riskLevel, int timeoutMs, Enums.Idempotency idempotency, String owner,
            String version, Enums.CapabilityStatus status, List<String> utterances,
            List<String> keywords, List<String> requiredSlots, Enums.GuardrailOwner guardrailOwner) {
        this(capabilityId, name, type, granularity, parentCapabilityId, domains, description,
                supportedIntents, inputSchema, outputSchema, preconditions, sideEffects, riskLevel,
                timeoutMs, idempotency, owner, version, status, utterances, keywords, requiredSlots,
                guardrailOwner, Boolean.TRUE, null, null, Boolean.TRUE,
                null, List.of(), List.of(), List.of());
    }

    public CapabilityCard(
            String capabilityId, String name, Enums.CapabilityType type, Enums.Granularity granularity,
            String parentCapabilityId, List<String> domains, String description,
            List<String> supportedIntents, Map<String, Object> inputSchema,
            Map<String, Object> outputSchema, List<String> preconditions, List<String> sideEffects,
            RiskLevel riskLevel, int timeoutMs, Enums.Idempotency idempotency, String owner,
            String version, Enums.CapabilityStatus status, List<String> utterances,
            List<String> keywords, List<String> requiredSlots, Enums.GuardrailOwner guardrailOwner,
            Boolean principalRequired) {
        this(capabilityId, name, type, granularity, parentCapabilityId, domains, description,
                supportedIntents, inputSchema, outputSchema, preconditions, sideEffects, riskLevel,
                timeoutMs, idempotency, owner, version, status, utterances, keywords, requiredSlots,
                guardrailOwner, principalRequired, null, null, Boolean.TRUE,
                null, List.of(), List.of(), List.of());
    }

    public EffectiveLoopAccess effectiveLoopAccess() {
        if (status != Enums.CapabilityStatus.ACTIVE || loopAccess == LoopAccess.DENY) {
            return EffectiveLoopAccess.DENY;
        }
        if (riskLevel == RiskLevel.R0 && !hasSideEffects()
                && confirmationPolicy == ConfirmationPolicy.NONE
                && loopAccess != LoopAccess.PROPOSE_ONLY) {
            return EffectiveLoopAccess.AUTO_READ_ONLY;
        }
        return EffectiveLoopAccess.PROPOSE_ONLY;
    }

    /**
     * 提参归属。
     *
     * <p>不设成独立字段而是从 {@code requiredSlots} 派生，因为两者一旦能各说各话，
     * 就会出现「声明 MAIN 却一个槽位都没列」这种自相矛盾的卡——它既没告诉主 Agent
     * 该问什么，又剥夺了子 Agent 自己问的权利，用户表现为系统既不问也不办。
     * 派生让「声明」这个动作本身成为唯一的授权凭据。
     *
     * <p>参数确实为零的能力（如余额查询）落在 AGENT 一侧，行为上与 MAIN 空集无差别：
     * 主 Agent 没东西可提交，直接下发。
     */
    public Enums.SlotOwner slotOwner() {
        return requiredSlots.isEmpty() ? Enums.SlotOwner.AGENT : Enums.SlotOwner.MAIN;
    }

    /**
     * 执行这张卡会不会改变外部世界。
     *
     * <p>两个来源取或，不是取风险等级一个：{@code riskLevel} 是合规视角的分级，
     * {@code sideEffects} 是领域方自己列的动作清单。一张标着 R0 却列了「发送短信」的卡
     * 多半是分级填错了，但在填错被纠正之前，按「有副作用」对待的代价只是多一次拦截，
     * 反过来的代价是在上下文不可信时把短信真发出去。
     */
    public boolean hasSideEffects() {
        return riskLevel != RiskLevel.R0 || !sideEffects.isEmpty();
    }

    /**
     * 筛出主 Agent 有权提交给本能力的槽位。
     *
     * <p>抽槽发生在选中能力之前，抽到的东西必然多于任何一张卡声明的范围。
     * 「查信用卡余额」会抽出 {@code cardType}，但余额查询卡没声明它——把它一并塞进
     * {@code UnifiedTask}，等于主 Agent 替账户领域定义了一个它从未承认的入参，
     * 而这个参数的语义、校验和出错责任都在领域方。宁可少给，让子 Agent 自己问。
     */
    public Map<String, Object> ownedSlots(Map<String, Object> extracted) {
        if (extracted == null || extracted.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashSet<String> declared = new java.util.LinkedHashSet<>(requiredSlots);
        Object rawProperties = inputSchema.get("properties");
        if (rawProperties instanceof Map<?, ?> properties) {
            properties.keySet().forEach(key -> declared.add(String.valueOf(key)));
        }
        if (declared.isEmpty()) return Map.of();
        Map<String, Object> owned = new java.util.LinkedHashMap<>();
        for (String slot : declared) {
            Object value = extracted.get(slot);
            if (value != null && !String.valueOf(value).isBlank()) {
                owned.put(slot, value);
            }
        }
        return owned;
    }

    /**
     * 拼出用于 embedding 的文档侧文本。
     *
     * <p>Qwen3-Embedding 是 instruction-aware 的，**文档侧不拼检索指令**，只有 query 侧拼
     * （实施架构 §2.5.6 落地约束 1）。这里刻意不接受 instruction 参数，避免调用方拼错。
     */
    public String embeddingDocument() {
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (description != null && !description.isBlank()) {
            sb.append('。').append(description);
        }
        if (!supportedIntents.isEmpty()) {
            sb.append('。').append(String.join("、", supportedIntents));
        }
        if (!utterances.isEmpty()) {
            sb.append('。').append(String.join("；", utterances));
        }
        return sb.toString();
    }
}
