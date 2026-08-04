package com.huawei.finance.a2a;

import com.huawei.finance.a2a.node.CapabilityKeywordProjector;
import com.huawei.finance.a2a.node.GoalCapabilityResolver;
import com.huawei.finance.a2a.node.KeywordGoalResolver;
import com.huawei.finance.a2a.node.ScaffoldNodeFactory;
import com.huawei.finance.a2a.node.TechDomainNodeFactory;
import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.port.TechDomainAgent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** A2A 网关装配。 */
@AutoConfiguration
@EnableConfigurationProperties(A2AProperties.class)
public class A2AConfiguration {

    /**
     * 卡资产位置。
     *
     * <p>默认走**文件路径**而不是 classpath:资产走 Git 仓而不是打进 jar
     * （见 {@code huawei.finance.agent.registry.assets-path} 那句注释:改一句话术不必重新构建）。
     * 写成 {@code classpath*:} 的话生产上一张卡都投不出来,而且是静默的——
     * 路由表空着，所有委托都回 DOMAIN_NOT_OPEN，看起来像「26 个域都没交付」。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentCardProjector agentCardProjector(A2AProperties props) {
        return new AgentCardProjector(props.resolveCardLocations());
    }

    /**
     * 路由表。
     *
     * <p>{@code ObjectProvider} 注入节点:阶段 2 只有入口一个 AgentNode，
     * 26 个域节点是阶段 3b 的交付物。用 {@code List} 注入会在没有任何节点时启动失败,
     * 把「域还没交付」变成「服务起不来」。
     */
    @Bean
    @ConditionalOnMissingBean
    public GoalCapabilityResolver goalCapabilityResolver(A2AProperties props) {
        return new KeywordGoalResolver(
                new CapabilityKeywordProjector(props.resolveCapabilityLocations()).project());
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentCardRegistry agentCardRegistry(AgentCardProjector projector,
                                               ObjectProvider<AgentNode> nodes,
                                               ObjectProvider<TechDomainAgent> domainAgents,
                                               GoalCapabilityResolver goalResolver) {
        List<AgentCard> cards = projector.project();

        List<AgentNode> all = new java.util.ArrayList<>(nodes.orderedStream().toList());

        // 有 TechDomainAgent 的域自动升级为真节点（阶段 3b）。自动而不是让每域各写一份适配：
        // 26 份适配会各自长出差异，而 A2A 这条线的价值恰恰建立在「所有域回执行为一致」上。
        // 显式注册的 AgentNode 优先——行内要自己接管某个域时，不该被这里的自动升级顶掉
        java.util.Set<String> taken = new java.util.LinkedHashSet<>();
        all.forEach(n -> taken.add(n.agentId()));
        TechDomainNodeFactory.upgrade(domainAgents.orderedStream().toList(), goalResolver)
                .stream()
                .filter(n -> !taken.contains(n.agentId()))
                .forEach(all::add);

        // 剩下的域补占位节点：它交付的是身份与显式失败，不是能力（v0.3 §5.4）。
        // 不补的话那个域在路由表里根本不存在，用户得到的是「不支持」而不是
        // 「该业务暂未开放」——前者会被当成需求缺失去排期
        all.addAll(ScaffoldNodeFactory.fillGaps(cards, all));
        return new AgentCardRegistry(cards, all);
    }

    /**
     * 台账默认走 Postgres。
     *
     * <p>没有 {@code JdbcTemplate} 时才回落到进程内实现——那只发生在不连库的测试里。
     * 默认方向必须是「守得住 §6.2 第 2 条唯一约束」的那个,
     * 因为默认值出错的场景恰恰是没人专门配过它的环境。
     */
    @Bean
    @ConditionalOnMissingBean
    public DelegationStore delegationStore() {
        return new InMemoryDelegationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public A2AGateway a2aGateway(AgentCardRegistry registry, DelegationStore store,
                                 A2AProperties props, MeterRegistry meterRegistry,
                                 ObjectProvider<Tracer> tracers) {
        return new A2AGateway(registry, store, props, meterRegistry, Clock.systemUTC(),
                tracers.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public DelegationClient delegationClient(A2AGateway gateway, A2AProperties props,
                                             MeterRegistry meterRegistry) {
        return new DelegationClient(gateway, props, meterRegistry, Clock.systemUTC());
    }
}
