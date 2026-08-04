package com.huawei.finance.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.GuardrailHook;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import org.redisson.api.RedissonClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 外置出去的行内特定常量，改了配置是否**真的生效**。
 *
 * <p>这类改造有个很好骗过自己的失败方式：新建了 properties 类、写进了 yml、
 * 文档里也说「已外置」，但消费点还在读那个 static final。编译通过、原有用例全绿，
 * 只有行内改了配置发现没反应时才会暴露——而那时他们多半以为是自己配错了。
 *
 * <p>所以断言方式是：把配置改成一个明显不同的值，验证行为跟着变。
 * 只断言「properties 类里有这个字段」证不到任何事。
 */
// classes 必须显式给：本类在 com.huawei.finance.arch 下，Spring 向上搜到 com.huawei.finance 就到头了，
// 找不到 com.huawei.finance.product.mobilebanking 里的启动类
@SpringBootTest(classes = {
        com.huawei.finance.product.mobilebanking.MobileBankingAssistantApplication.class,
        ExternalizedConstantsTest.TestBeans.class
}, properties = {
        "huawei.finance.agent.registry.build-index-on-startup=false",
        "huawei.finance.sample.mock.enabled=false",
        "spring.flyway.enabled=false",
        // 与基线默认 50000 明显不同，且跨过下面那笔 20000 的金额
        "huawei.finance.agent.guardrail.single-transfer-limit=10000",
        "huawei.finance.agent.response.default-currency=CNY "
})
class ExternalizedConstantsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        RedissonClient testRedissonClient() {
            return (RedissonClient) Proxy.newProxyInstance(
                    RedissonClient.class.getClassLoader(), new Class<?>[]{RedissonClient.class},
                    (proxy, method, args) -> {
                        Class<?> type = method.getReturnType();
                        if (!type.isPrimitive()) {
                            return null;
                        }
                        if (type == boolean.class) {
                            return false;
                        }
                        if (type == char.class) {
                            return '\0';
                        }
                        return 0;
                    });
        }
    }

    @Autowired
    private GuardrailHook guardrail;
    @Autowired
    private AssetBundle bundle;
    @Autowired
    private com.huawei.finance.response.ResponseProperties responseProps;

    @Test
    @DisplayName("单笔限额：配成 1 万后，2 万的转账被判越限")
    void transferLimitComesFromConfiguration() {
        GuardrailCheck check = guardrail.check(transferOf("20000"), transferCard());
        assertThat(check.codes()).contains("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("单笔限额：同一笔金额在基线默认 5 万下本应放行（证明上一条不是恒真）")
    void theSameAmountWouldPassUnderTheBaselineDefault() {
        // 不改配置的护栏。若上一条在任何限额下都判越限，这条会红——
        // 那说明失败原因是别的（比如金额压根没解析），限额配置其实没接通
        GuardrailHook baseline = new com.huawei.finance.orchestrator.guardrail.PolicyGuardrail(
                new com.huawei.finance.orchestrator.guardrail.GuardrailProperties());
        assertThat(baseline.check(transferOf("20000"), transferCard()).codes())
                .doesNotContain("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("默认币种：读到的是配置里的值，不是编译进去的 ¥")
    void currencyComesFromConfiguration() {
        assertThat(responseProps.getDefaultCurrency()).isEqualTo("CNY ");
    }

    @Test
    @DisplayName("新意图探测线：来自 fusion 资产，且高于召回达标线")
    void intentSignalThresholdLivesInTheFusionAsset() {
        var thresholds = bundle.fusion().getThresholds();
        assertThat(thresholds.getIntentSignalMin()).isEqualTo(0.7);
        assertThat(thresholds.getIntentSignalMin())
                .as("探测线必须高于召回达标线：把槽位补充误判成新意图会中断在办任务，"
                        + "而漏判只是多问一轮。两条阈值现在同处一份资产，就是为了改一条时能看见另一条")
                .isGreaterThan(thresholds.getTop1Min());
    }

    private static UnifiedTask transferOf(String amount) {
        return new UnifiedTask("t-1", "trace-1", Enums.TaskSource.FAST_PATH, "转账",
                "cap.transfer", Map.of("payee", "张三", "amount", amount),
                RiskLevel.R2, Map.of("confirmed", true),
                GuardrailCheck.pending(), null, List.of(), null);
    }

    private CapabilityCard transferCard() {
        CapabilityCard card = bundle.capability("cap.transfer");
        assertThat(card).as("资产里应当有转账能力卡").isNotNull();
        return card;
    }
}
