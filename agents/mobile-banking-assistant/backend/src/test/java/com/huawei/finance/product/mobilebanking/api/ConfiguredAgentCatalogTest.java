package com.huawei.finance.product.mobilebanking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfiguredAgentCatalogTest {

    @TempDir
    Path temp;

    @Test
    void discoversStandardAgentDefinitionsWithoutClaimingTheyAreOnline() throws Exception {
        Path mobileAssets = createAgent(
                "mobile-banking-assistant",
                "agent.mobile-banking-assistant",
                "手机银行助手",
                "[entry, routing]",
                "[mobile_banking]");
        createAgent("account", "agent.account", "账户助手", "[domain]", "[account]");

        List<ConfiguredAgentCatalog.ConfiguredAgent> agents =
                ConfiguredAgentCatalog.load(mobileAssets, List.of());

        assertThat(agents).extracting(ConfiguredAgentCatalog.ConfiguredAgent::agentId)
                .containsExactly("agent.account", "agent.mobile-banking-assistant");
        assertThat(agents).filteredOn(agent -> agent.agentId().equals("agent.account"))
                .singleElement()
                .satisfies(agent -> {
                    assertThat(agent.displayName()).isEqualTo("账户助手");
                    assertThat(agent.roles()).containsExactly("domain");
                    assertThat(agent.domains()).containsExactly("account");
                });
    }

    private Path createAgent(
            String directory, String id, String displayName, String roles, String domains) throws Exception {
        Path home = temp.resolve("agents").resolve(directory);
        Path assets = Files.createDirectories(home.resolve("assets"));
        Files.writeString(home.resolve("agent.yaml"), """
                agent:
                  id: %s
                  displayName: %s
                  roles: %s
                  domains: %s
                """.formatted(id, displayName, roles, domains));
        return assets;
    }
}
