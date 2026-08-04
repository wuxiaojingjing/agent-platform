package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.port.GuardrailHook;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;

/**
 * TCK 能不能抓住违规实现。
 *
 * <p>{@code Baseline*ContractTest} 证明的是 TCK 不苛刻——合规实现能跑过。
 * 那一半单独成立没有意义：一套断言全被注释掉的用例同样能让合规实现跑过。
 * 这里补另一半，用几个**故意违规**的实现去跑同一套 TCK，验证它确实变红。
 *
 * <p>做法是在 JUnit 里嵌套启动一次 JUnit（{@code Launcher} API），
 * 因为违规用例必须失败，而失败的用例不能出现在正常的测试报告里。
 */
class TckDetectsViolationsTest {

    private static final AssetBundle BUNDLE =
            new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());

    @Test
    @DisplayName("按业务参数去重的 Agent 会被 TCK 判红")
    void catchesAgentThatDedupesOnBusinessParameters() {
        Failures failures = run(DedupeByParametersAgentTest.class);
        assertThat(failures.names())
                .as("这个实现按「收款人+金额」去重，换了幂等键的第二笔会被当成重复吞掉。"
                        + "TCK 若抓不住它，交出去就是一张废纸")
                .contains("differentKeysAreDifferentTransactions");
    }

    @Test
    @DisplayName("无幂等键也照常执行的 Agent 会被 TCK 判红")
    void catchesAgentThatIgnoresIdempotencyKey() {
        Failures failures = run(ExecutesWithoutKeyAgentTest.class);
        assertThat(failures.names()).contains("refusesTaskWithoutIdempotencyKey");
    }

    @Test
    @DisplayName("R2 缺确认却放行的护栏会被 TCK 判红")
    void catchesGuardrailThatSkipsConfirmation() {
        Failures failures = run(AlwaysPassGuardrailTest.class);
        assertThat(failures.names())
                .contains("r2WithoutConfirmationIsRejected", "missingRequiredSlotIsRejected",
                        "nullCardIsRejected");
    }

    private static Failures run(Class<?> testClass) {
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(
                LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(testClass))
                        .build(),
                listener);
        var summary = listener.getSummary();
        assertThat(summary.getTestsFoundCount())
                .as("一个用例都没跑到，说明嵌套启动的选择器写错了，下面的断言会假绿")
                .isGreaterThan(0);
        List<String> names = summary.getFailures().stream()
                .map(f -> f.getTestIdentifier().getSource()
                        .map(Object::toString).orElse(f.getTestIdentifier().getDisplayName()))
                .toList();
        return new Failures(names);
    }

    private record Failures(List<String> raw) {
        List<String> names() {
            return raw.stream()
                    .map(s -> {
                        int i = s.indexOf("methodName = '");
                        if (i < 0) {
                            return s;
                        }
                        String rest = s.substring(i + "methodName = '".length());
                        return rest.substring(0, rest.indexOf('\''));
                    })
                    .toList();
        }
    }

    // ---- 故意违规的实现，只用于被 TCK 抓 ----

    /** 按业务参数去重：给同一个人连转两笔一样的钱时会静默吞掉第二笔。 */
    static class DedupeByParametersAgentTest extends DomainAgentContract {
        private final DomainAgent shared = new DomainAgent() {
            private final Map<String, TaskResult> seen = new HashMap<>();

            @Override
            public boolean supports(String capabilityId) {
                return "cap.transfer".equals(capabilityId);
            }

            @Override
            public TaskResult execute(UnifiedTask task) {
                if (!task.executable()) {
                    return failed(task);
                }
                String businessKey = String.valueOf(task.parameters());
                TaskResult prior = seen.get(businessKey);
                if (prior != null) {
                    return failed(task);
                }
                TaskResult ok = new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                        Enums.FailureClass.NONE, Map.of("ok", true), task.idempotencyKey(),
                        task.guardrailCheck());
                seen.put(businessKey, ok);
                return ok;
            }
        };

        @Override
        protected DomainAgent agent() {
            return shared;
        }

        @Override
        protected String capabilityId() {
            return "cap.transfer";
        }

        @Override
        protected Map<String, Object> validParameters() {
            return Map.of("payee", "张三", "amount", "100");
        }
    }

    /** 不查幂等键，来了就执行。 */
    static class ExecutesWithoutKeyAgentTest extends DomainAgentContract {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        protected DomainAgent agent() {
            return new DomainAgent() {
                @Override
                public boolean supports(String capabilityId) {
                    return "cap.transfer".equals(capabilityId);
                }

                @Override
                public TaskResult execute(UnifiedTask task) {
                    executions.incrementAndGet();
                    return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                            Enums.FailureClass.NONE, Map.of("ok", true),
                            task.idempotencyKey(), task.guardrailCheck());
                }
            };
        }

        @Override
        protected String capabilityId() {
            return "cap.transfer";
        }

        @Override
        protected Map<String, Object> validParameters() {
            return Map.of("payee", "张三", "amount", "100");
        }
    }

    /** 永远放行的护栏。 */
    static class AlwaysPassGuardrailTest extends GuardrailHookContract {
        @Override
        protected GuardrailHook guardrail() {
            return (draft, card) -> GuardrailCheck.passed();
        }

        @Override
        protected CapabilityCard r2Card() {
            return BUNDLE.capability("cap.transfer");
        }

        @Override
        protected Map<String, Object> completeParameters() {
            return Map.of("payee", "张三", "amount", "100");
        }
    }

    private static TaskResult failed(UnifiedTask task) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED,
                Enums.FailureClass.FATAL, Map.of("error", "DUPLICATE"),
                null, task.guardrailCheck());
    }
}
