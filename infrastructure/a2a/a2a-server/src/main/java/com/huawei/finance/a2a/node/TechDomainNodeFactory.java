package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把在册的 {@link TechDomainAgent} 逐个升级成真 {@link AgentNode}（阶段 3b）。
 *
 * <p>「升级」在这里是自动的:一个域只要有能跑的 {@code TechDomainAgent}，就自动获得
 * Card 可发现 + 一条 GOAL/TASK 主路径 + 未开放能力显式失败——阶段 3b 对每域的三条要求。
 * 要求每个域另写一份 A2A 适配的话,26 份适配会各自长出差异,而 A2A 这条线的价值
 * 恰恰建立在「所有域的回执行为一致」上。
 *
 * <p><b>同一个 agentId 有多个 TechDomainAgent 时全部并入一个节点。</b>
 * 这不是防御性代码:{@code cap.card.replace} 就是账户域与信用卡域都承接的能力,
 * 一个域也完全可以由多个 Agent 分担实现。若各自建节点，同一个 agentId 会有两个节点,
 * 路由投给谁取决于遍历顺序——正是双射门禁要拦的那种不确定性。
 */
public final class TechDomainNodeFactory {

    private static final Logger log = LoggerFactory.getLogger(TechDomainNodeFactory.class);

    private TechDomainNodeFactory() {
    }

    /**
     * @param agents 在册的科技域 Agent
     * @param goalResolver GOAL → 能力的解析器
     * @return 每个 agentId 一个节点
     */
    public static List<AgentNode> upgrade(List<TechDomainAgent> agents,
                                          GoalCapabilityResolver goalResolver) {
        Map<String, List<TechDomainAgent>> byAgentId = new LinkedHashMap<>();
        for (TechDomainAgent agent : agents) {
            byAgentId.computeIfAbsent(agent.agentId(), k -> new ArrayList<>()).add(agent);
        }

        List<AgentNode> nodes = new ArrayList<>();
        byAgentId.forEach((agentId, group) -> {
            TechDomainAgent merged = group.size() == 1 ? group.get(0) : new CompositeAgent(group);

            // 一条能力都不承接的 Agent 不升级——它就是个占位实现（如 ScaffoldDomainAgent）。
            //
            // 升级了反而会丢掉「未开放」这个语义:占位实现的 supports 恒为 false，
            // 于是 GOAL 在解析这一步就落不到任何能力，回的是 NOT_MINE。
            // 而 NOT_MINE 的含义是「投错域了」，入口会改投——改投一圈仍然无人接，
            // 用户最后得到的是「不支持这个业务」，而事实是「这个业务还没开放」。
            // 前者会被当成需求缺失去排期，后者本该去问那个域的交付进度（v0.3 §5.4）
            if (merged.advertisedCapabilities().isEmpty()) {
                log.info("科技域无承接能力，保留占位节点 agent={} domain={}",
                        agentId, merged.techDomainCode());
                return;
            }

            nodes.add(new DomainAgentNode(agentId, List.of(merged.techDomainCode()),
                    new DomainAgentExecutor(merged, goalResolver), true));
            log.info("科技域节点已升级 agent={} domain={} 实现数={} 承接能力数={}",
                    agentId, merged.techDomainCode(), group.size(),
                    merged.advertisedCapabilities().size());
        });
        return nodes;
    }

    /**
     * 同一 agentId 下多个实现的合并视图。
     *
     * <p>{@code supports} 取并集，执行交给第一个认这条能力的。两个实现都认同一条能力时
     * 取靠前的那个——顺序由 Spring 的 {@code @Order} 决定，这与中控侧的选法一致,
     * 好让同一条能力无论走 A2A 还是走中控都落到同一个实现上。
     */
    private record CompositeAgent(List<TechDomainAgent> members) implements TechDomainAgent {

        @Override
        public String agentId() {
            return members.get(0).agentId();
        }

        @Override
        public String techDomainCode() {
            return members.get(0).techDomainCode();
        }

        @Override
        public boolean supports(String capabilityId) {
            return members.stream().anyMatch(m -> m.supports(capabilityId));
        }

        @Override
        public java.util.Set<String> advertisedCapabilities() {
            // 取并集。清单只用于展示，路由真值仍是 supports——
            // 以清单派单会出现「清单里有、真派过去没人接」的空转
            return members.stream()
                    .flatMap(m -> m.advertisedCapabilities().stream())
                    .collect(java.util.stream.Collectors.toCollection(
                            java.util.LinkedHashSet::new));
        }

        @Override
        public com.huawei.finance.contracts.model.TaskResult execute(
                com.huawei.finance.contracts.model.UnifiedTask task) {
            for (TechDomainAgent member : members) {
                if (member.supports(task.capabilityId())) {
                    return member.execute(task);
                }
            }
            throw new IllegalStateException("没有成员承接该能力 capability=" + task.capabilityId());
        }
    }
}
