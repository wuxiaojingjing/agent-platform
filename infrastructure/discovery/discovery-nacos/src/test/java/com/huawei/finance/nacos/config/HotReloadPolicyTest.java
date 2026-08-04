package com.huawei.finance.nacos.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 热更新白名单的判定。这套判定错了不会抛异常，只会让人以为改生效了（或没生效）。 */
class HotReloadPolicyTest {

    @Test
    @DisplayName("默认白名单为空时，任何键推下来都不生效")
    void nothingIsHotByDefault() {
        var policy = new HotReloadPolicy(List.of());

        var plan = policy.plan(Map.of("huawei.finance.agent.fastpath.topK", "20"), Map.of("huawei.finance.agent.fastpath.topK", "50"));

        assertThat(plan.applied()).isEmpty();
        assertThat(plan.ignored()).containsExactly("huawei.finance.agent.fastpath.topK");
    }

    @Test
    @DisplayName("白名单内的键按前缀匹配后生效")
    void whitelistedKeysApply() {
        var policy = new HotReloadPolicy(List.of("huawei.finance.agent.fastpath."));

        var plan = policy.plan(
                Map.of("huawei.finance.agent.fastpath.topK", "20", "huawei.finance.agent.response.locale", "zh"),
                Map.of("huawei.finance.agent.fastpath.topK", "50", "huawei.finance.agent.response.locale", "en"));

        assertThat(plan.applied()).containsExactly(Map.entry("huawei.finance.agent.fastpath.topK", "50"));
        assertThat(plan.ignored()).containsExactly("huawei.finance.agent.response.locale");
    }

    /**
     * 删一行必须真的删掉。
     *
     * <p>若删除被当成「没变化」，那么在 Nacos 上删掉一个覆盖值之后，系统会继续用那个覆盖值，
     * 而控制台上已经看不到它了——这种状态没有任何办法从外部观察出来。
     */
    @Test
    @DisplayName("键被删掉时还原成没有，而不是留着旧值")
    void removedKeysAreRemoved() {
        var policy = new HotReloadPolicy(List.of("huawei.finance.agent."));

        var plan = policy.plan(Map.of("huawei.finance.agent.fastpath.topK", "20"), Map.of());

        assertThat(plan.applied()).containsKey("huawei.finance.agent.fastpath.topK");
        assertThat(plan.applied().get("huawei.finance.agent.fastpath.topK")).isNull();
    }

    @Test
    @DisplayName("连接参数与 spring.* 即使进了白名单也拒绝热更新")
    void connectionAndFrameworkKeysAreNeverHot() {
        var policy = new HotReloadPolicy(List.of("huawei.finance.agent.", "spring."));

        var plan = policy.plan(
                Map.of("huawei.finance.agent.nacos.serverAddr", "a:8848", "spring.datasource.url", "jdbc:x"),
                Map.of("huawei.finance.agent.nacos.serverAddr", "b:8848", "spring.datasource.url", "jdbc:y"));

        assertThat(plan.applied()).isEmpty();
        assertThat(plan.rejected()).containsExactly("huawei.finance.agent.nacos.serverAddr", "spring.datasource.url");
    }

    @Test
    @DisplayName("值没变的键不算变更")
    void unchangedKeysAreNotReported() {
        var policy = new HotReloadPolicy(List.of("huawei.finance.agent."));

        var plan = policy.plan(Map.of("huawei.finance.agent.a", "1"), Map.of("huawei.finance.agent.a", "1"));

        assertThat(plan.isEmpty()).isTrue();
    }
}
