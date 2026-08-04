package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.orchestrator.continuation.ContinuationContracts.ConfirmationStrength;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Context;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Event;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.Resolution;
import com.huawei.finance.orchestrator.continuation.ContinuationUnderstandingModel;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Offline continuation-model fixture for REST integration tests.
 *
 * <p>The production fallback intentionally does not understand natural-language phrases when the
 * model is unavailable. These fixed outputs stand in for validated model responses so the tests
 * exercise the model -> PolicyGate -> Runtime chain without adding language rules to production.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContinuationModelFixtureConfiguration {

    @Bean
    @Primary
    ContinuationUnderstandingModel continuationModelFixture() {
        return (tenant, agent, session, input, context) -> fixture(input, context);
    }

    private static Resolution fixture(String input, Context context) {
        String text = input == null ? "" : input.trim();
        if (context.pendingSwitch() != null) {
            if ("好，切换".equals(text)) {
                return accepted(Event.SWITCH_ACCEPT, context.pendingSwitch().switchId(), null);
            }
            if ("继续当前任务".equals(text)) {
                return accepted(Event.SWITCH_REJECT, context.pendingSwitch().switchId(), null);
            }
            return unresolved("AMBIGUOUS_PENDING_SWITCH");
        }

        var foreground = context.foreground();
        if (foreground == null) {
            if ("继续".equals(text) && context.suspended().size() > 1) {
                return unresolved("AMBIGUOUS_RESUME");
            }
            return unresolved("MODEL_FIXTURE_NO_MATCH");
        }
        if ("确认执行转账".equals(text)) {
            return new Resolution(Event.CONFIRM, foreground.runtimeRef(), Map.of(), null, .99,
                    "MODEL_EXPLICIT_ACTION", ConfirmationStrength.EXPLICIT_ACTION);
        }
        if ("继续".equals(text) && foreground.allowedEvents().contains(Event.REVIEW_ACCEPT)) {
            return accepted(Event.REVIEW_ACCEPT, foreground.runtimeRef(), null);
        }
        if ("尾号8821那张".equals(text)
                && foreground.allowedEvents().contains(Event.FILL_SLOT)
                && foreground.allowedSlotsAndValues().containsKey("cardRef")) {
            return new Resolution(Event.FILL_SLOT, foreground.runtimeRef(),
                    Map.of("cardRef", text), null, .99, "MODEL_FIXTURE");
        }
        if (("先办查余额".equals(text) || "1".equals(text) || "接着办".equals(text))
                && foreground.allowedEvents().contains(Event.CONTINUE_CURRENT)) {
            return accepted(Event.CONTINUE_CURRENT, foreground.runtimeRef(), null);
        }
        if (("先查信用卡账单".equals(text)
                || "先给张三转 1000".equals(text)
                || "算了，看看我的理财持仓".equals(text))
                && foreground.allowedEvents().contains(Event.SWITCH_TO_NEW_GOAL)) {
            return accepted(Event.SWITCH_TO_NEW_GOAL, foreground.runtimeRef(), text);
        }
        return unresolved("MODEL_FIXTURE_NO_MATCH");
    }

    private static Resolution accepted(Event event, String targetRef, String newGoalSpan) {
        return new Resolution(event, targetRef, Map.of(), newGoalSpan, .99, "MODEL_FIXTURE");
    }

    private static Resolution unresolved(String reason) {
        return new Resolution(Event.UNRESOLVED, null, Map.of(), null, 0, reason);
    }
}
