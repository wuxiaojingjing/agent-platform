package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.registry.asset.ResponsePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponsePolicyTest {
    @Test
    void mostSpecificRuleWinsWithoutChangingUnmatchedDefault() {
        ResponsePolicy policy = new ResponsePolicy();
        ResponsePolicy.Rule defaults = new ResponsePolicy.Rule();
        defaults.setMode(Enums.RenderMode.TEMPLATE);
        policy.setDefaults(defaults);
        ResponsePolicy.Rule phase = new ResponsePolicy.Rule();
        phase.setPhase("FINAL");
        phase.setMode(Enums.RenderMode.POLISH);
        ResponsePolicy.Rule agent = new ResponsePolicy.Rule();
        agent.setAgent("agent.account");
        agent.setPhase("FINAL");
        agent.setMode(Enums.RenderMode.GENERATE);
        policy.setRules(List.of(phase, agent));

        assertThat(policy.resolve("tenant", "agent.account", "scene", Enums.ResponsePhase.FINAL).mode())
                .isEqualTo(Enums.RenderMode.GENERATE);
        assertThat(policy.resolve("tenant", "agent.other", "scene", Enums.ResponsePhase.FINAL).mode())
                .isEqualTo(Enums.RenderMode.POLISH);
        assertThat(policy.resolve("tenant", "agent.other", "scene", Enums.ResponsePhase.CLARIFY).mode())
                .isEqualTo(Enums.RenderMode.TEMPLATE);
    }
}
