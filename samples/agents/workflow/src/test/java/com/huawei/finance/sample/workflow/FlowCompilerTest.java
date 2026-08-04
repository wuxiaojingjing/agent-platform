package com.huawei.finance.sample.workflow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编译期校验。
 *
 * <p>这些错误若漏到运行期，暴露方式都是「某个字段悄悄为空」或「某个分支永远不走」，
 * 不会有异常，不会有告警，只会在事后对账时发现有笔业务的流水号是空的。
 */
class FlowCompilerTest {

    private final StubOperations.CallLog log = new StubOperations.CallLog();

    private FlowCompiler compiler() {
        return new FlowCompiler(StubOperations.all(log, new BigDecimal("50000"), new BigDecimal("10000")));
    }

    @Test
    @DisplayName("操作名找不到实现，编译就失败")
    void rejectsUnknownOperation() {
        FlowSpec spec = new FlowSpec("cap.x", "1.0.0", "",
                List.of(step("a", "payment.doesNotExist")), Map.of());

        assertThatThrownBy(() -> compiler().compile(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("找不到操作实现")
                .hasMessageContaining("payment.doesNotExist");
    }

    @Test
    @DisplayName("步骤 id 重复，编译就失败")
    void rejectsDuplicateStepId() {
        FlowSpec spec = new FlowSpec("cap.x", "1.0.0", "",
                List.of(step("a", "payment.resolveDefaultAccount"),
                        step("a", "payment.submitTransfer")), Map.of());

        assertThatThrownBy(() -> compiler().compile(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("步骤 id 重复");
    }

    @Test
    @DisplayName("条件引用后面才产生的步骤，编译就失败")
    void rejectsForwardReferenceInCondition() {
        FlowSpec spec = new FlowSpec("cap.x", "1.0.0", "",
                List.of(new FlowSpec.Step("a", "payment.resolveDefaultAccount", "b.ok", null, null),
                        step("b", "payment.submitTransfer")), Map.of());

        assertThatThrownBy(() -> compiler().compile(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未执行的步骤");
    }

    @Test
    @DisplayName("结果映射引用不存在的步骤，编译就失败")
    void rejectsUnknownStepInResultMapping() {
        FlowSpec spec = new FlowSpec("cap.x", "1.0.0", "",
                List.of(step("a", "payment.resolveDefaultAccount")),
                Map.of("serialNo", "nosuch.serialNo"));

        assertThatThrownBy(() -> compiler().compile(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在的步骤");
    }

    @Test
    @DisplayName("两个实现抢同一个操作名，装配就失败")
    void rejectsDuplicateOperationName() {
        assertThatThrownBy(() -> new FlowCompiler(List.of(
                new StubOperations.SendNotice(log), new StubOperations.SendNotice(log))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("操作名重复");
    }

    @Test
    @DisplayName("没有步骤的流程不算流程")
    void rejectsEmptyFlow() {
        FlowSpec spec = new FlowSpec("cap.x", "1.0.0", "", List.of(), Map.of());

        assertThatThrownBy(() -> compiler().compile(spec))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有任何步骤");
    }

    @Test
    @DisplayName("引用任务参数与已跑过的步骤是合法的")
    void acceptsBackwardReferences() {
        FlowSpec spec = new FlowSpec("cap.x", "1.0.0", "",
                List.of(step("account", "payment.resolveDefaultAccount"),
                        new FlowSpec.Step("notify", "payment.sendNotice", "account.accountNo", null, null)),
                Map.of("from", "account.accountNo", "payee", "params.payee"));

        assertThatCode(() -> compiler().compile(spec)).doesNotThrowAnyException();
    }

    private static FlowSpec.Step step(String id, String operation) {
        return new FlowSpec.Step(id, operation, null, null, null);
    }
}
