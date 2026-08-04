package com.huawei.finance.product.mobilebanking.console;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.finance.common.context.AgentProperties;
import com.huawei.finance.intent.cache.DecisionCacheControl;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

class ConsoleSettingsEndpointTest {

    @Test
    void decisionCacheCanBeToggledAndClosingClearsExistingDecisions() throws Exception {
        ConsoleProperties properties = new ConsoleProperties();
        properties.setWriteEnabled(true);
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setId("agent.mobile-banking-assistant");
        InMemoryDecisionCacheControl control = new InMemoryDecisionCacheControl();
        ConsoleController controller = new ConsoleController(
                null, null, null, null, null, null, null, properties, agentProperties,
                null, null, null, Optional.empty(), Optional.of(control));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/internal/console/decision-cache-control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(put("/internal/console/decision-cache-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.cleared").value(0));

        mvc.perform(put("/internal/console/decision-cache-control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.cleared").value(3));
    }

    @Test
    void exposesAllFrontendRuntimeParameters() throws Exception {
        ConsoleProperties properties = new ConsoleProperties();
        properties.setDisplayName("测试手机银行");
        properties.setDefaultChannel("MOBILE_BANK");
        properties.setChannels(List.of("WEB", "MOBILE_BANK"));
        properties.setPageOptions(List.of("home", "account"));
        properties.setUserStateOptions(List.of("LOGGED_IN", "GUEST"));
        properties.setRefreshInterval(Duration.ofSeconds(9));
        properties.setAutoRefreshEnabled(false);

        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setId("agent.mobile-banking-assistant");

        ConsoleController controller = new ConsoleController(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                properties,
                agentProperties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/internal/console/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.id").value("agent.mobile-banking-assistant"))
                .andExpect(jsonPath("$.agent.displayName").value("测试手机银行"))
                .andExpect(jsonPath("$.tenantDefaults.userId").value("u-console"))
                .andExpect(jsonPath("$.tenantDefaults.channel").value("MOBILE_BANK"))
                .andExpect(jsonPath("$.tenantDefaults.channels[1]").value("MOBILE_BANK"))
                .andExpect(jsonPath("$.chat.pages[1]").value("account"))
                .andExpect(jsonPath("$.chat.userStates[1]").value("GUEST"))
                .andExpect(jsonPath("$.chat.exampleQueries[0]").value("查一下我的余额"))
                .andExpect(jsonPath("$.operations.refreshIntervalMillis").value(9000))
                .andExpect(jsonPath("$.operations.autoRefreshEnabled").value(false));
    }

    @Test
    void productionResponsePolicyWriteIsRejectedBeforeAssetMutation() throws Exception {
        ConsoleProperties properties = new ConsoleProperties();
        properties.setWriteEnabled(false);
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setId("agent.mobile-banking-assistant");

        ConsoleController controller = new ConsoleController(
                null, null, null, null, null, null, null, properties, agentProperties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(put("/internal/console/response-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"response-policy-v2","promptVersion":"prompt-v2",
                                 "defaults":{"tenant":"*","agent":"*","scene":"*","phase":"*",
                                  "mode":"MODEL_SELECT","model":"response-model","templateSet":["tpl.a"],
                                  "temperature":0.2,"maxTokens":320},"rules":[]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "控制台写入未开启；生产回复策略必须通过 Git/MR 发布"));
    }

    private static final class InMemoryDecisionCacheControl implements DecisionCacheControl {
        private boolean enabled;

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public long clear() {
            return 3;
        }
    }
}
