package com.huawei.finance.nacos.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** {@code spring.config.import: nacos:...} 的解析，以及它到底有没有被注册上。 */
class NacosConfigDataLocationResolverTest {

    private final NacosConfigDataLocationResolver resolver = new NacosConfigDataLocationResolver();

    /**
     * 注册没上是**静默**失败：Boot 会把 {@code nacos:mobile-banking-assistant.yaml} 当成一个本地文件路径去找，
     * 带 {@code optional:} 时连报错都没有，服务照常起来，只是所有配置都来自本地。
     */
    @Test
    @DisplayName("两个扩展点确实注册在 spring.factories 里")
    void extensionPointsAreRegistered() throws Exception {
        // 读文件而不是 SpringFactoriesLoader.load：Boot 自带的解析器需要构造参数，
        // 真去实例化会先在它们身上炸掉，测不到我们关心的那两行
        StringBuilder declarations = new StringBuilder();
        var resources = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (resources.hasMoreElements()) {
            try (var in = resources.nextElement().openStream()) {
                declarations.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        assertThat(declarations.toString())
                .contains(NacosConfigDataLocationResolver.class.getName())
                .contains(NacosConfigDataLoader.class.getName());
    }

    @Test
    @DisplayName("只认 nacos: 前缀")
    void onlyHandlesTheNacosPrefix() {
        assertThat(resolver.isResolvable(context(Map.of()), ConfigDataLocation.of("nacos:a.yaml"))).isTrue();
        assertThat(resolver.isResolvable(context(Map.of()), ConfigDataLocation.of("classpath:a.yaml"))).isFalse();
    }

    @Test
    @DisplayName("默认用配置里的分组，也可以就地写 组/dataId 覆盖")
    void resolvesGroupAndDataId() {
        var ctx = context(Map.of("huawei.finance.agent.nacos.config.group", "HUAWEI_FINANCE_AGENT_APP"));

        List<NacosConfigDataResource> defaults = resolver.resolve(ctx, ConfigDataLocation.of("nacos:app.yaml"));
        List<NacosConfigDataResource> overridden =
                resolver.resolve(ctx, ConfigDataLocation.of("nacos:OTHER/shared.yaml"));

        assertThat(defaults).singleElement()
                .satisfies(r -> {
                    assertThat(r.group()).isEqualTo("HUAWEI_FINANCE_AGENT_APP");
                    assertThat(r.dataId()).isEqualTo("app.yaml");
                });
        assertThat(overridden).singleElement()
                .satisfies(r -> {
                    assertThat(r.group()).isEqualTo("OTHER");
                    assertThat(r.dataId()).isEqualTo("shared.yaml");
                });
    }

    @Test
    @DisplayName("只写前缀不给 dataId 时当场报错，不去猜一个默认值")
    void refusesEmptyDataId() {
        assertThatThrownBy(() -> resolver.resolve(context(Map.of()), ConfigDataLocation.of("nacos:")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataId");
    }

    /**
     * 连接参数在这个阶段只能来自本地与环境变量。
     *
     * <p>「去哪里取配置」自己不能是取回来的配置——这条听起来显然，但把 serverAddr
     * 写进 Nacos 公共配置里是真会有人做的事。
     */
    @Test
    @DisplayName("客户端工厂在解析阶段就建好并交给后续阶段复用")
    void registersTheClientFactoryForLaterStages() {
        ConfigurableBootstrapContext bootstrap = new DefaultBootstrapContext();
        var ctx = context(Map.of("huawei.finance.agent.nacos.server-addr", "nacos-a:8848"), bootstrap);

        resolver.resolve(ctx, ConfigDataLocation.of("nacos:app.yaml"));

        var factory = bootstrap.get(NacosConfigServiceFactory.class);
        assertThat(factory.properties().getServerAddr()).isEqualTo("nacos-a:8848");
        assertThat(factory.isInitialized())
                .as("解析阶段不该真去连，连接推迟到 loader 取配置时")
                .isFalse();
    }

    private static ConfigDataLocationResolverContext context(Map<String, Object> properties) {
        return context(properties, new DefaultBootstrapContext());
    }

    private static ConfigDataLocationResolverContext context(
            Map<String, Object> properties, ConfigurableBootstrapContext bootstrap) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("用例", properties));
        Binder binder = Binder.get(environment);
        return new ConfigDataLocationResolverContext() {
            @Override
            public Binder getBinder() {
                return binder;
            }

            @Override
            public ConfigDataResource getParent() {
                return null;
            }

            @Override
            public ConfigurableBootstrapContext getBootstrapContext() {
                return bootstrap;
            }
        };
    }
}
