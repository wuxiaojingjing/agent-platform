package com.huawei.finance.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.testkit.AgentYamlFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDefinitionTest {

    @TempDir
    Path home;

    @Test
    void loadsScaffoldDefinition() throws Exception {
        Files.writeString(home.resolve("agent.yaml"),
                AgentYamlFixture.scaffold("agent.loan_service", "loan_service"));

        AgentDefinition definition = AgentDefinition.load(home);

        assertThat(definition.id()).isEqualTo("agent.loan_service");
        assertThat(definition.mode()).isEqualTo("scaffold");
        assertThat(definition.domains()).containsExactly("loan_service");
    }

    @Test
    void rejectsMissingDomains() throws Exception {
        Files.writeString(home.resolve("agent.yaml"), """
                agent:
                  id: agent.invalid
                implementation:
                  mode: scaffold
                """);

        assertThatThrownBy(() -> AgentDefinition.load(home))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("agent.domains");
    }

    @Test
    void extensionWithoutLoaderPathFailsFast() throws Exception {
        Files.writeString(home.resolve("agent.yaml"), AgentYamlFixture.extension(
                "agent.account", "account", "com.huawei.finance:account"));

        assertThatThrownBy(() -> AgentDefinition.load(home))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOADER_PATH");
    }
}
