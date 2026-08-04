package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.product.mobilebanking.console.EngineRegistry;
import com.huawei.finance.product.mobilebanking.console.RecentDecisions;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.runtime.spi.DecisionRecorder;
import com.huawei.finance.runtime.spi.PostOrchestrationHook;
import com.huawei.finance.runtime.spi.RuntimeEngines;
import com.huawei.finance.runtime.spi.RuntimeEnginesSource;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 手机银行助手产品侧能力接到 runtime SPI。
 *
 * <p>{@link SessionAffinity} 已是 {@code SessionAffinityPort} 的 {@code @Component}，
 * 不再另挂 Bean，避免同类型双注册。
 */
@Configuration
public class RuntimeBridgeConfiguration {

    @Bean
    public RuntimeEnginesSource runtimeEnginesSource(EngineRegistry engines) {
        return () -> {
            var snap = engines.current();
            return new RuntimeEngines(snap.bundle(), snap.intentEngine(), snap.planner(), snap.renderer());
        };
    }

    @Bean
    public DecisionRecorder decisionRecorder(RecentDecisions recent) {
        return recent::record;
    }

    @Bean
    public PostOrchestrationHook productCompareHook(ProductCompareFetcher productCompare) {
        return (ctx, decision, intentPlan, bundle, outcome, lease, defaultSlots) -> {
            if (decision.decision() != Decision.STATIC_PLAN
                    || decision.reasonCode() != ReasonCode.CROSS_DOMAIN
                    || !productCompare.supports(intentPlan)) {
                return Optional.empty();
            }
            Map<String, Object> compared = productCompare.fetch(ctx, intentPlan, bundle, lease);
            return Optional.of(compared.isEmpty() ? defaultSlots : compared);
        };
    }
}
