package com.huawei.finance.a2a;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AgentCard：A2A 发现与 Schema 匹配用的投影（架构草案 v0.2 §10、v0.3 §6）。
 *
 * <p>投影自各资产根下 capabilities/agents 下 yaml（共享根或 agents 下各域 assets）——那些文件是各节点的父卡，
 * AgentCard 是它们对外可见的那一面。**只为 1+26 个 AgentNode 生成**:
 * 纯执行器（节点内 {@code DomainAgent}）不出现在路由表里。
 *
 * <p><b>{@code riskLevel} 在这里只是元数据，不是决策依据。</b>上游不得依据它决定跳过确认——
 * 确认要求由被调方的本地能力卡与本地护栏决定（§6.4 第 3 条）。放在卡上是为了
 * 发现阶段能做粗筛与展示,一旦被用来跳过确认,风险判定就变成了上游可以远程推翻的东西。
 */
public record AgentCard(
        String agentId,
        String techDomainCode,
        String name,
        String description,
        List<String> domains,
        String riskLevel,
        long timeoutMs,
        String owner,
        String version,
        Status status,
        Map<String, Object> inputSchema,
        ContextContract contextContract,
        String runtime) {

    /**
     * 节点状态。
     *
     * <p>{@link #SCAFFOLD} 是 v0.3 §5.4 要求的显式状态:「节点尚未交付」，
     * 目录可见但 GOAL/TASK 应明确失败，不得假成功。面客碰到未开放域走能力未开放 / 转人工，
     * 不走假数据。
     */
    public enum Status { ACTIVE, SCAFFOLD, RETIRED }

    public AgentCard {
        Objects.requireNonNull(agentId, "AgentCard 必须有 agentId");
        domains = domains == null ? List.of() : List.copyOf(domains);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        status = status == null ? Status.ACTIVE : status;
        contextContract = contextContract == null ? ContextContract.FULL : contextContract;
        runtime = runtime == null || runtime.isBlank() ? "java" : runtime;
    }

    public AgentCard(String agentId, String techDomainCode, String name, String description,
                     List<String> domains, String riskLevel, long timeoutMs, String owner,
                     String version, Status status, Map<String, Object> inputSchema) {
        this(agentId, techDomainCode, name, description, domains, riskLevel, timeoutMs,
                owner, version, status, inputSchema, ContextContract.FULL, "java");
    }

    public enum ContextContract {
        FULL,
        STATELESS_READ_ONLY
    }

    /** 已建成、可接委托。SCAFFOLD 可被发现但接委托一律 DOMAIN_NOT_OPEN。 */
    public boolean deliverable() {
        return status == Status.ACTIVE;
    }
}
