package com.huawei.finance.a2a.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 已有 TechDomainAgent 自动升级成 A2A 节点（架构草案 v0.3 阶段 3b）。 */
class TechDomainNodeFactoryTest {

    private static final GoalCapabilityResolver NO_GOAL = new KeywordGoalResolver(Map.of());

    @Test
    @DisplayName("有承接能力的域自动升级，不需要各域另写一份 A2A 适配")
    void agentsWithCapabilitiesAreUpgraded() {
        var nodes = TechDomainNodeFactory.upgrade(
                List.of(agent("agent.account", "account", Set.of("cap.account.balance.query"))),
                NO_GOAL);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).agentId()).isEqualTo("agent.account");
    }

    @Test
    @DisplayName("一条能力都不承接的占位实现不升级：否则「未开放」会退化成「投错域」")
    void placeholderAgentsAreNotUpgraded() {
        // 占位实现的 supports 恒为 false，升级后 GOAL 会回 NOT_MINE。
        // NOT_MINE 让入口改投，改投一圈无人接，用户看到的是「不支持这个业务」——
        // 而事实是「这个业务还没开放」。两者该去的地方完全不同
        var nodes = TechDomainNodeFactory.upgrade(
                List.of(agent("agent.pension", "pension", Set.of())), NO_GOAL);

        assertThat(nodes).isEmpty();
    }

    @Test
    @DisplayName("同一 agentId 多个实现并成一个节点，不是两个")
    void multipleImplementationsMergeIntoOneNode() {
        // cap.card.replace 就是账户域与信用卡域都承接的能力，一个域也可以由多个 Agent 分担。
        // 各自建节点会让同一个 agentId 有两个节点，路由投给谁取决于遍历顺序
        var nodes = TechDomainNodeFactory.upgrade(List.of(
                agent("agent.account", "account", Set.of("cap.account.balance.query")),
                agent("agent.account", "account", Set.of("cap.card.replace"))), NO_GOAL);

        assertThat(nodes).hasSize(1);
    }

    @Test
    @DisplayName("并成一个节点后，两个实现承接的能力都能办")
    void mergedNodeServesAllMembers() {
        var nodes = TechDomainNodeFactory.upgrade(List.of(
                agent("agent.account", "account", Set.of("cap.account.balance.query")),
                agent("agent.account", "account", Set.of("cap.card.replace"))), NO_GOAL);
        AgentNode node = nodes.get(0);

        assertThat(node.handle(DomainAgentNodeTestSupport.task("agent.account",
                "cap.account.balance.query")).outcome())
                .isEqualTo(com.huawei.finance.contracts.a2a.DelegationOutcome.SUCCEEDED);
        assertThat(node.handle(DomainAgentNodeTestSupport.task("agent.account",
                "cap.card.replace")).outcome())
                .as("第二个实现承接的能力也必须能办，否则合并等于丢了一半能力")
                .isEqualTo(com.huawei.finance.contracts.a2a.DelegationOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("多个域各自成节点")
    void differentDomainsGetTheirOwnNodes() {
        var nodes = TechDomainNodeFactory.upgrade(List.of(
                agent("agent.account", "account", Set.of("cap.account.balance.query")),
                agent("agent.creditcard", "creditcard_service",
                        Set.of("cap.creditcard.bill.query"))), NO_GOAL);

        assertThat(nodes).hasSize(2);
        assertThat(nodes.stream().map(AgentNode::agentId))
                .containsExactly("agent.account", "agent.creditcard");
    }

    private static TechDomainAgent agent(String agentId, String domain, Set<String> caps) {
        return new TechDomainAgent() {
            @Override
            public String agentId() {
                return agentId;
            }

            @Override
            public String techDomainCode() {
                return domain;
            }

            @Override
            public boolean supports(String capabilityId) {
                return caps.contains(capabilityId);
            }

            @Override
            public Set<String> advertisedCapabilities() {
                return caps;
            }

            @Override
            public TaskResult execute(UnifiedTask task) {
                return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                        Enums.FailureClass.NONE, Map.of("done", "true"),
                        task.idempotencyKey(), GuardrailCheck.passed());
            }
        };
    }
}
