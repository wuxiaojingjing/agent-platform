package com.huawei.finance.nacos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.nacos.NacosProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 推送落到 Environment 与 {@code @ConfigurationProperties} 上这一段。
 *
 * <p>不连真 Nacos：这里要验的是「解析出来的键怎么落地」，与传输无关。
 */
class NacosConfigRefresherTest {

    private static final String SOURCE = "nacos:DEFAULT_GROUP/mobile-banking-assistant.yaml";

    @Test
    @DisplayName("白名单内的推送会改到 Environment，并重新绑定配置对象")
    void appliesAndRebinds() {
        try (var context = contextWith("huawei.finance.agent.demo.topK", "20")) {
            var demo = context.getBean(DemoProperties.class);
            assertThat(demo.getTopK()).isEqualTo(20);

            refresher(context, List.of("huawei.finance.agent.demo.")).apply(SOURCE, "mobile-banking-assistant.yaml", "huawei:\n  finance:\n    agent:\n      demo:\n        topK: 50\n");

            assertThat(context.getEnvironment().getProperty("huawei.finance.agent.demo.topK")).isEqualTo("50");
            // 同一个 Bean 实例被就地改写，引用不变——不销毁重建是刻意的
            assertThat(context.getBean(DemoProperties.class)).isSameAs(demo);
            assertThat(demo.getTopK()).isEqualTo(50);
        }
    }

    @Test
    @DisplayName("白名单外的推送不落地，配置对象保持原值")
    void ignoresKeysOutsideTheWhitelist() {
        try (var context = contextWith("huawei.finance.agent.demo.topK", "20")) {
            var demo = context.getBean(DemoProperties.class);

            refresher(context, List.of()).apply(SOURCE, "mobile-banking-assistant.yaml", "huawei:\n  finance:\n    agent:\n      demo:\n        topK: 50\n");

            assertThat(context.getEnvironment().getProperty("huawei.finance.agent.demo.topK")).isEqualTo("20");
            assertThat(demo.getTopK()).isEqualTo(20);
        }
    }

    /**
     * 环境变量必须压得住配置中心。
     *
     * <p>出事时运维要能就地压掉一个坏值，而不是先去配置中心改。若热更新把自己插到最前面，
     * 这条路会静默失效——静默到只有在真出事时才会发现。
     */
    @Test
    @DisplayName("热更新不改变优先级：更高优先级的来源仍然压得住")
    void doesNotStealPrecedence() {
        try (var context = contextWith("huawei.finance.agent.demo.topK", "20")) {
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("运维现场压制", Map.of("huawei.finance.agent.demo.topK", "7")));

            refresher(context, List.of("huawei.finance.agent.demo.")).apply(SOURCE, "mobile-banking-assistant.yaml", "huawei:\n  finance:\n    agent:\n      demo:\n        topK: 50\n");

            assertThat(context.getEnvironment().getProperty("huawei.finance.agent.demo.topK")).isEqualTo("7");
        }
    }

    @Test
    @DisplayName("推下来一份坏 YAML 时保持原配置不变")
    void keepsOldValuesWhenPushedContentIsBroken() {
        try (var context = contextWith("huawei.finance.agent.demo.topK", "20")) {
            refresher(context, List.of("huawei.finance.agent.demo."))
                    .apply(SOURCE, "mobile-banking-assistant.yaml", "huawei:\n  finance:\n    agent:\n      demo:\n       topK: [unclosed\n");

            assertThat(context.getEnvironment().getProperty("huawei.finance.agent.demo.topK")).isEqualTo("20");
        }
    }

    @Test
    @DisplayName("生效时发事件，全被忽略时不发")
    void publishesEventOnlyWhenSomethingChanged() {
        try (var context = contextWith("huawei.finance.agent.demo.topK", "20")) {
            var recorder = context.getBean(EventRecorder.class);

            refresher(context, List.of()).apply(SOURCE, "mobile-banking-assistant.yaml", "huawei:\n  finance:\n    agent:\n      demo:\n        topK: 50\n");
            assertThat(recorder.events).isEmpty();

            refresher(context, List.of("huawei.finance.agent.demo.")).apply(SOURCE, "mobile-banking-assistant.yaml", "huawei:\n  finance:\n    agent:\n      demo:\n        topK: 50\n");
            assertThat(recorder.events).hasSize(1);
            assertThat(recorder.events.getFirst().dataId()).isEqualTo("mobile-banking-assistant.yaml");
        }
    }

    private static NacosConfigRefresher refresher(
            AnnotationConfigApplicationContext context, List<String> refreshable) {
        NacosProperties properties = new NacosProperties();
        properties.getConfig().setRefreshable(refreshable);
        return new NacosConfigRefresher(
                context.getEnvironment(),
                context,
                new NacosConfigServiceFactory(properties),
                properties);
    }

    private static AnnotationConfigApplicationContext contextWith(String key, String value) {
        var environment = new StandardEnvironment();
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put(key, value);
        // 名字与 ConfigData 导入进来的那个一致，替换逻辑才找得到它
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, initial));
        var context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(TestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties
    static class TestConfig {

        @Bean
        DemoProperties demoProperties() {
            return new DemoProperties();
        }

        @Bean
        EventRecorder eventRecorder() {
            return new EventRecorder();
        }
    }

    /** setter 绑定的配置对象，可以就地重新绑定。 */
    @ConfigurationProperties(prefix = "huawei.finance.agent.demo")
    static class DemoProperties {

        private int topK;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }
    }

    static class EventRecorder {

        final List<NacosConfigChangedEvent> events = new java.util.ArrayList<>();

        @org.springframework.context.event.EventListener
        void on(NacosConfigChangedEvent event) {
            events.add(event);
        }
    }
}
