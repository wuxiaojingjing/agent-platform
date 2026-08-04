package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 阶段 D1：共享根不再放已交付域 TOOL 主资产；合并加载仍完整。
 */
class D1AssetLayoutTest {

    private static final Set<String> DELIVERED_TOOL_FILES = Set.of(
            "account.yaml",
            "payment.yaml",
            "creditcard.yaml",
            "wealth.yaml",
            "fund.yaml",
            "insurance.yaml");

    private static AssetBundle bundle;
    private static Path shared;

    @BeforeAll
    static void load() {
        shared = AgentAssetLocations.requireAssets();
        bundle = new AssetLoader(new ContractValidator()).load(shared);
    }

    @Test
    @DisplayName("共享根 capabilities 浅层不再放已交付域 TOOL 文件")
    void sharedRootHasNoDeliveredToolFiles() throws IOException {
        Path caps = shared.resolve("capabilities");
        if (!Files.isDirectory(caps)) {
            return;
        }
        try (Stream<Path> files = Files.list(caps)) {
            Set<String> shallow = files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
            assertThat(shallow).doesNotContainAnyElementsOf(DELIVERED_TOOL_FILES);
        }
        assertThat(Files.isDirectory(caps.resolve("agents")))
                .as("AGENT 父卡应已迁出共享根 capabilities/agents")
                .isFalse();
    }

    @Test
    @DisplayName("合并加载仍含 26 张 AGENT 父卡与已交付叶子能力")
    void mergedLoadKeepsAgentsAndDeliveredLeaves() {
        Set<String> agentIds = bundle.capabilities().stream()
                .filter(c -> c.type() == Enums.CapabilityType.AGENT)
                .map(CapabilityCard::capabilityId)
                .filter(id -> id.startsWith("agent.") && !id.equals("agent.nav"))
                .collect(Collectors.toSet());
        assertThat(agentIds).hasSize(26);

        assertThat(bundle.capabilities().stream().map(CapabilityCard::capabilityId))
                .contains(
                        "cap.account.balance.query",
                        "cap.transfer",
                        "cap.creditcard.bill.query",
                        "cap.wealth.holding.query",
                        "cap.fund.product.query",
                        "cap.insurance.product.query");
    }

    @Test
    @DisplayName("能发现 agents/<id>/assets 且入口 nav 资产在 mobile-banking-assistant 下")
    void agentRootsAndEntryNavAreDiscoverable() {
        assertThat(AgentAssetLocations.discoverAgentAssetRoots(shared)).isNotEmpty();
        assertThat(AgentAssetLocations.findInAssetRoots(shared, "menus/nav-meta.yaml")).isPresent();
        assertThat(AgentAssetLocations.findInAssetRoots(shared, "capabilities/nav/nav-menus.yaml")).isPresent();
    }
}
