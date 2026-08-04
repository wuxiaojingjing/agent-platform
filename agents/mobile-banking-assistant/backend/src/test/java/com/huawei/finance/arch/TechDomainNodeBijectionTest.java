package com.huawei.finance.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.a2a.A2AProperties;
import com.huawei.finance.a2a.AgentCard;
import com.huawei.finance.a2a.AgentCardProjector;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 附录 F 域码 ↔ AgentNode 双射（架构草案 v0.3 前提第 9 条、阶段 3b 门禁）。
 *
 * <p>「对应且仅对应一个」两头都要守:少了，那个域的委托无处可投;
 * 多了，同一个域有两个可发现节点，域路由投给谁取决于遍历顺序——
 * 那种不确定性在压测里偶发，在生产里表现为「同样的话有时办成有时说不支持」。
 *
 * <p><b>映射走卡上声明的 {@code domains}，不做 agentId 字符串推导。</b>
 * {@code agent.creditcard} 的域码是 {@code creditcard_service}:agentId 保留历史短码
 * 是有意的（TOOLS 的 {@code parentCapabilityId} 指着它），
 * 靠 agentId 减前缀去猜域码会把这张卡误判成缺失,进而逼出一次没必要的重命名。
 * 资产头部那句「不能靠别名」说的是同一件事。
 */
class TechDomainNodeBijectionTest {

    /** 入口 Agent 是第 27 个节点，不占用附录 F 码（前提第 9 条）。 */
    private static final String ENTRY_AGENT_ID = "mobile-banking-assistant";

    /** 卡位置取 A2AProperties 多根默认值（共享根 + 各域 assets）。 */
    private static final java.util.List<String> CARD_LOCATIONS =
            new A2AProperties().resolveCardLocations();

    private static final Path DOMAIN_TABLE =
            AgentAssetLocations.requireAssets().resolve("domains/tech-domains.yaml");

    @Test
    @DisplayName("26 个科技域码与节点卡一一对应")
    void everyTechDomainHasExactlyOneNode() throws IOException {
        Set<String> codes = techDomainCodes();
        Map<String, List<String>> byDomain = nodesByDomain();

        Set<String> missing = new TreeSet<>(codes);
        missing.removeAll(byDomain.keySet());
        assertThat(missing)
                .as("这些科技域没有可发现的 AgentNode，委托无处可投")
                .isEmpty();

        Set<String> unknown = new TreeSet<>(byDomain.keySet());
        unknown.removeAll(codes);
        assertThat(unknown)
                .as("这些节点卡声明的域码不在附录 F 里——要么域码写错，要么域表没更新")
                .isEmpty();

        Map<String, List<String>> duplicated = new TreeMap<>();
        byDomain.forEach((domain, agents) -> {
            if (agents.size() > 1) {
                duplicated.put(domain, agents);
            }
        });
        assertThat(duplicated)
                .as("一个域有多个可发现节点，域路由投给谁取决于遍历顺序")
                .isEmpty();
    }

    @Test
    @DisplayName("入口不占用附录 F 域码")
    void entryAgentDoesNotConsumeATechDomainCode() {
        Map<String, List<String>> byDomain = nodesByDomain();

        assertThat(byDomain.values().stream().flatMap(List::stream))
                .as("入口是第 27 个节点，它出现在域码映射里说明它抢了某个域的位置")
                .doesNotContain(ENTRY_AGENT_ID);
    }

    @Test
    @DisplayName("每个节点卡都有明确状态：未交付的域也要能被发现")
    void everyNodeHasAnExplicitStatus() {
        // 未交付的域交付的是「身份与显式失败」（阶段 3b）：目录里看得见，
        // 接委托时明确回 DOMAIN_NOT_OPEN。看不见的话，域路由会当它不存在，
        // 用户得到的是「不支持」而不是「该业务暂未开放」——归因完全不同
        List<AgentCard> cards = projectCards();

        assertThat(cards).isNotEmpty();
        assertThat(cards).allSatisfy(card ->
                assertThat(card.status()).as("卡 %s 缺状态", card.agentId()).isNotNull());
    }

    /**
     * 直接读仓库里的资产文件，不走 classpath。
     *
     * <p>资产走 Git 仓不打进 jar（见 {@code huawei.finance.agent.registry.assets-path}），
     * 所以这套门禁也必须读同一份文件——读 test resources 里的副本，
     * 守的就是副本而不是真资产。
     */
    private static List<AgentCard> projectCards() {
        List<AgentCard> cards = new AgentCardProjector(CARD_LOCATIONS).project();
        assertThat(cards)
                .as("一张节点卡都没投出来：位置 %s 不对，这套门禁在空转", CARD_LOCATIONS)
                .isNotEmpty();
        return cards;
    }

    /** 域码 → 声明了该域的节点 agentId 列表。 */
    private static Map<String, List<String>> nodesByDomain() {
        Map<String, List<String>> byDomain = new LinkedHashMap<>();
        for (AgentCard card : projectCards()) {
            for (String domain : card.domains()) {
                byDomain.computeIfAbsent(domain, k -> new java.util.ArrayList<>())
                        .add(card.agentId());
            }
        }
        return byDomain;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> techDomainCodes() throws IOException {
        try (var in = Files.newInputStream(DOMAIN_TABLE)) {
            Map<String, Object> root = new Yaml().load(in);
            Set<String> codes = new LinkedHashSet<>();
            for (Object row : (List<Object>) root.get("domains")) {
                codes.add(String.valueOf(((Map<String, Object>) row).get("code")));
            }
            return codes;
        }
    }
}
