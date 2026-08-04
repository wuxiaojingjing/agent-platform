package com.huawei.finance.nacos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

/**
 * 配置中心连不上时，服务该起来还是不该起来。
 *
 * <p>两种都要能做到，而且**由带不带 {@code optional:} 决定**，不是由我们替所有人选一个。
 * 面客服务通常选前者（本地默认值先扛住），强依赖配置中心的批处理选后者。
 */
class NacosUnavailableStartupTest {

    /** 指向一个必然连不上的地址：1 端口不会有人监听，连接立刻被拒，不用等超时 */
    private static final String UNREACHABLE = "127.0.0.1:1";

    @Test
    @DisplayName("带 optional: 时用本地默认值照常启动")
    void startsWithLocalDefaultsWhenOptional() {
        try (var context = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "huawei.finance.agent.nacos.server-addr=" + UNREACHABLE,
                        "huawei.finance.agent.nacos.config.timeout-ms=300",
                        "spring.config.import=optional:nacos:不存在的配置.yaml")
                .run()) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    /**
     * 不带 optional: 就该起不来。
     *
     * <p>这条同样重要：若我们把失败一律吞掉，那么「必须从配置中心拿到参数才能开工」的场景
     * 会带着一份本地兜底配置静默上线，而没人知道它用的不是配置中心那一份。
     */
    @Test
    @DisplayName("不带 optional: 时拒绝启动，而不是静默用本地值")
    void refusesToStartWhenMandatory() {
        var application = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "huawei.finance.agent.nacos.server-addr=" + UNREACHABLE,
                        "huawei.finance.agent.nacos.config.timeout-ms=300",
                        "spring.config.import=nacos:不存在的配置.yaml");

        assertThatThrownBy(application::run)
                .hasMessageContaining("不存在的配置.yaml");
    }

    @Configuration
    static class Empty {
    }
}
