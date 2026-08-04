package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.fastpath.cache.DecisionCacheKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 架构草案阶段 1：agentId 必须参与一级出口缓存键。
 *
 * <p>形状照 {@link TenantCacheIsolationTest}：两个 Agent 问同一句话不得共用一条记录，
 * 而这种串味在单 Agent 测试环境里永远复现不出来。
 */
class AgentCacheIsolationTest {

    private static RequestContext ctx(String agentId) {
        return new RequestContext("t", "s", "u", "space-a", agentId, "APP", "home", "NORMAL", false);
    }

    private static String key(RequestContext ctx) {
        return DecisionCacheKey.of(ctx, "转账给张三", "asset-v1", "emb-v1", "instr-v1", "prompt-v1",
                List.of("userState"), Map.of("userState", "NORMAL"));
    }

    @Test
    @DisplayName("同一句话在不同 Agent 下算出不同的键")
    void 不同Agent不共键() {
        assertThat(key(ctx("agent.entry"))).isNotEqualTo(key(ctx("agent.account")));
    }

    @Test
    @DisplayName("同 Agent 同输入稳定命中，否则命中率归零")
    void 同Agent同键() {
        assertThat(key(ctx("agent.entry"))).isEqualTo(key(ctx("agent.entry")));
    }

    @Test
    @DisplayName("未写 agentId 时回落到 agent.entry，与显式入口 Agent 共键")
    void 默认落入口Agent() {
        RequestContext implicit = new RequestContext("t", "s", "u", "space-a",
                "APP", "home", "NORMAL", false);
        assertThat(key(implicit)).isEqualTo(key(ctx(RequestContext.AGENT_ENTRY)));
    }
}
