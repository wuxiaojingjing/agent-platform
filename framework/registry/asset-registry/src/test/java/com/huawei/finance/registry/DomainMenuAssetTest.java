package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.MenuCatalog;
import com.huawei.finance.registry.asset.TechDomainCatalog;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 附录 F 科技域与菜单树入库验收。
 */
class DomainMenuAssetTest {

    private static AssetBundle bundle;

    @BeforeAll
    static void load() {
        bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    @Test
    @DisplayName("科技域 26 条，与附录 F 中文名一致")
    void techDomainsMatchAppendixF() {
        TechDomainCatalog catalog = bundle.techDomains();
        assertThat(catalog.getDomains()).hasSize(26);
        Set<String> names = catalog.getDomains().stream()
                .map(TechDomainCatalog.Domain::getName)
                .collect(Collectors.toSet());
        assertThat(names).contains(
                "理财服务", "基金服务", "保险服务", "财富聚合服务",
                "账户管理", "转账服务", "支付管理", "信用卡服务");
        assertThat(catalog.canonicalize("fund")).isEqualTo("fund_service");
        assertThat(catalog.canonicalize("wealth")).isEqualTo("wealth_aggregate");
        assertThat(catalog.canonicalize("payment")).isEqualTo("payment");
    }

    @Test
    @DisplayName("菜单树清洗后超过 300 条，且无 unmapped")
    void menuTreeIsPopulated() {
        MenuCatalog menus = bundle.menus();
        assertThat(menus.getMenus().size()).isGreaterThan(300);
        assertThat(menus.getMenus())
                .noneMatch(m -> "unmapped".equals(m.getTechDomain()));
        assertThat(menus.getMenus().stream().filter(m -> "workflow".equals(m.getKind())).count())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("能力卡 domains 均可被科技域表识别")
    void capabilityDomainsAreRegistered() {
        TechDomainCatalog catalog = bundle.techDomains();
        bundle.capabilities().forEach(card ->
                assertThat(card.domains())
                        .as(card.capabilityId())
                        .allMatch(catalog::isKnown));
    }

    @Test
    @DisplayName("每个科技域至少有一张 AGENT 父卡")
    void everyTechDomainHasAgentParentCard() {
        Set<String> agentDomains = bundle.capabilities().stream()
                .filter(c -> c.type() == com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT)
                .flatMap(c -> c.domains().stream())
                .collect(Collectors.toSet());
        Set<String> techCodes = bundle.techDomains().getDomains().stream()
                .map(TechDomainCatalog.Domain::getCode)
                .collect(Collectors.toSet());
        assertThat(agentDomains).containsAll(techCodes);
    }

    @Test
    @DisplayName("域路由候选只含 AGENT，且与 TOOL 召回集合不相交（阶段 1.5）")
    void domainRoutingIsAgentOnlyAndDisjointFromToolRecall() {
        var routing = bundle.domainRoutingCapabilities();
        var tools = bundle.recallableCapabilities();

        assertThat(routing).isNotEmpty();
        assertThat(routing).allMatch(c ->
                c.type() == com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT);
        assertThat(tools).allMatch(c ->
                c.type() != com.huawei.finance.contracts.model.Enums.CapabilityType.AGENT);

        Set<String> routingIds = routing.stream()
                .map(c -> c.capabilityId())
                .collect(Collectors.toSet());
        Set<String> toolIds = tools.stream()
                .map(c -> c.capabilityId())
                .collect(Collectors.toSet());
        assertThat(routingIds).doesNotContainAnyElementsOf(toolIds);
        assertThat(routingIds).contains("agent.account");
    }
}
