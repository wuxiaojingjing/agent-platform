package com.huawei.finance.obs;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.obs.trace.DecisionTrace;
import com.huawei.finance.obs.trace.DecisionTracePolicy;
import com.huawei.finance.obs.trace.MicrometerDecisionTrace;
import com.huawei.finance.obs.trace.PropertyBackedTracePolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 扩展点是不是真的能被覆盖。
 *
 * <p>这组用例守的是基线产品的核心承诺。{@code @ConditionalOnMissingBean} 写上去不等于
 * 生效——写成普通 {@code @Configuration} 再被 {@code @ComponentScan} 扫到时，
 * 谁先谁后取决于扫描顺序，覆盖就成了碰运气。而这类失效不会报错：使用方的实现被静默忽略，
 * 系统照基线的默认行为跑，只有等到有人核对 APM 里为什么还有业务关键词时才会发现。
 */
class ObsExtensionPointTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("使用方不管时，装基线的默认实现")
    void providesDefaultsWhenIntegratorSaysNothing() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DecisionTracePolicy.class);
            assertThat(context).getBean(DecisionTracePolicy.class)
                    .isInstanceOf(PropertyBackedTracePolicy.class);
            assertThat(context).getBean(DecisionTrace.class)
                    .isInstanceOf(MicrometerDecisionTrace.class);
        });
    }

    @Test
    @DisplayName("使用方声明了 DecisionTracePolicy，基线的默认实现必须让位")
    void integratorPolicyWins() {
        runner.withUserConfiguration(BankPolicyConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DecisionTracePolicy.class);
            DecisionTracePolicy policy = context.getBean(DecisionTracePolicy.class);
            assertThat(policy).isInstanceOf(BankPolicy.class);
            // 不只看类型：行内策略的取值必须真的是生效的那一份
            assertThat(policy.maxCandidates()).isEqualTo(1);
            assertThat(policy.includeEvidence()).isFalse();
        });
    }

    @Test
    @DisplayName("使用方整个换掉 DecisionTrace 实现也可以——行内自有 APM 埋点时走这条路")
    void integratorCanReplaceRecorderEntirely() {
        runner.withUserConfiguration(BankRecorderConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DecisionTrace.class);
            assertThat(context).getBean(DecisionTrace.class).isSameAs(DecisionTrace.NOOP);
        });
    }

    @Test
    @DisplayName("配置项能调默认策略的取值，不必为改个上限写代码")
    void propertiesTuneTheDefault() {
        runner.withPropertyValues(
                "huawei.finance.agent.obs.max-traced-candidates=9",
                "huawei.finance.agent.obs.include-evidence=true",
                "huawei.finance.agent.obs.include-suppressed=false").run(context -> {
            DecisionTracePolicy policy = context.getBean(DecisionTracePolicy.class);
            assertThat(policy.maxCandidates()).isEqualTo(9);
            assertThat(policy.includeEvidence()).isTrue();
            assertThat(policy.includeSuppressed()).isFalse();
        });
    }

    @Test
    @DisplayName("没有 Tracer Bean 时上下文照样起得来")
    void contextStartsWithoutTracer() {
        // runner 里压根没注册 Tracer。观测缺失导致启动失败这个方向搞反了代价很大：
        // Boot 4 升级时就因为 Tracer 没 Bean 让整个上下文起不来
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("没有 MeterRegistry Bean 时上下文照样起得来")
    void contextStartsWithoutMeterRegistry() {
        // 与上一条同理，只是这个坑更晚才踩到：Boot 4 把指标自动配置拆成了单独的 starter，
        // 行内那个只装 Agent Server 的进程没有 MeterRegistry Bean，
        // 而 MeterRegistry 起初是按类型直接注入的，那个进程第一次启动即失败
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObsAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DecisionTrace.class);
                });
    }

    @Test
    @DisplayName("退化的 registry 仍然可用，不是个会抛异常的空壳")
    void fallbackRegistryStillAcceptsMetrics() {
        // 退到 SimpleMeterRegistry 而不是传 null，是为了不让 recordPhase 里到处判空：
        // 那种判空一旦漏一处，代价就是运行期 NPE 打断业务链路——为了记指标而弄坏业务，
        // 方向完全反了
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObsAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(DecisionTrace.class))
                        .satisfies(trace -> trace.recordPhase("recall", 1_000_000L)));
    }

    @Configuration
    static class BankPolicyConfig {
        @Bean
        DecisionTracePolicy bankPolicy() {
            return new BankPolicy();
        }
    }

    /** 某行的红线：APM 里只留 top1，且任何业务关键词都不许出现。 */
    static class BankPolicy implements DecisionTracePolicy {
        @Override
        public int maxCandidates() {
            return 1;
        }

        @Override
        public boolean includeEvidence() {
            return false;
        }

        @Override
        public boolean includeSuppressed() {
            return false;
        }
    }

    @Configuration
    static class BankRecorderConfig {
        @Bean
        DecisionTrace bankRecorder() {
            return DecisionTrace.NOOP;
        }
    }
}
