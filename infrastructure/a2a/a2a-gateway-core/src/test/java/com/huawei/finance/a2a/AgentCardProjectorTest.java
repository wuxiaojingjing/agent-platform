package com.huawei.finance.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AgentCard 投影（架构草案 v0.3 §6）。
 *
 * <p>用本模块 test 资源里的样例卡，而不是 mobile-banking-assistant 的全量资产:这里要验的是投影规则，
 * 「26 个域的卡都在」是资产完整性，归 AssetSnapshotDiscipline 那边管。
 */
class AgentCardProjectorTest {

    private final AgentCardProjector projector =
            new AgentCardProjector("classpath*:test-agents/*.yaml");

    @Test
    @DisplayName("只投 type: AGENT——纯执行器不进 A2A 路由表")
    void onlyAgentNodesAreProjected() {
        List<AgentCard> cards = projector.project();

        assertThat(cards).extracting(AgentCard::agentId)
                .as("capability 粒度的卡不该出现：A2A 只寻址节点")
                .containsExactlyInAnyOrder("agent.account", "agent.transfer");
    }

    @Test
    @DisplayName("status 认不出时按未交付，不按已建成")
    void unknownStatusFallsBackToScaffold() {
        // 把状态写错的卡当成「可接委托」，委托会真的投过去；
        // 当成「未交付」最多是这个域暂时不可达，能被发现且不动钱
        AgentCard transfer = projector.project().stream()
                .filter(c -> c.agentId().equals("agent.transfer"))
                .findFirst().orElseThrow();

        assertThat(transfer.status()).isEqualTo(AgentCard.Status.SCAFFOLD);
        assertThat(transfer.deliverable()).isFalse();
    }

    @Test
    @DisplayName("DISABLED 资产态明确映射为不可交付，不作为未知状态")
    void disabledStatusMapsToScaffold() {
        AgentCard disabled = new AgentCardProjector("classpath*:test-agents-disabled/*.yaml")
                .project().getFirst();

        assertThat(disabled.status()).isEqualTo(AgentCard.Status.SCAFFOLD);
        assertThat(disabled.deliverable()).isFalse();
    }

    @Test
    @DisplayName("ACTIVE 卡可接委托，字段照卡投影")
    void activeCardProjectsFields() {
        AgentCard account = projector.project().stream()
                .filter(c -> c.agentId().equals("agent.account"))
                .findFirst().orElseThrow();

        assertThat(account.deliverable()).isTrue();
        assertThat(account.techDomainCode()).isEqualTo("account");
        assertThat(account.timeoutMs()).isEqualTo(5000);
        assertThat(account.riskLevel()).isEqualTo("R0");
    }

    @Test
    @DisplayName("一个域的卡写坏了，其余域照常在表里")
    void brokenCardDoesNotFailWholeRegistry() {
        // 26 个域的卡由各领域方维护。一个域的 YAML 写坏了不该让整个网关起不来——
        // 那会把「一个域的资产错」放大成「全平台不可用」
        List<AgentCard> cards = new AgentCardProjector("classpath*:test-agents-broken/*.yaml")
                .project();

        assertThat(cards).extracting(AgentCard::agentId).containsExactly("agent.ok");
    }
}
