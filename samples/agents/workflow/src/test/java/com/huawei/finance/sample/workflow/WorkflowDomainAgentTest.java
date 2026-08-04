package com.huawei.finance.sample.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 声明式办理流程的行为验收。
 *
 * <p>用真实的图引擎跑，不 mock 引擎：这个模块的全部价值就在于「编排交给引擎」，
 * 把引擎换成假的，测到的就只剩我们自己写的那点胶水。
 */
class WorkflowDomainAgentTest {

    private static final BigDecimal LIMIT = new BigDecimal("50000");
    private static final BigDecimal NOTICE_THRESHOLD = new BigDecimal("10000");

    private final StubOperations.CallLog log = new StubOperations.CallLog();

    private WorkflowDomainAgent agent(List<DomainOperation> operations) {
        List<FlowSpec> specs = new FlowSpecLoader().load(Path.of("src/test/resources/flows"));
        Map<String, FlowSpec> byCapability = Map.of(specs.get(0).capabilityId(), specs.get(0));
        return new WorkflowDomainAgent(byCapability, new FlowCompiler(operations));
    }

    private WorkflowDomainAgent agent() {
        return agent(StubOperations.all(log, LIMIT, NOTICE_THRESHOLD));
    }

    @Test
    @DisplayName("按声明顺序办完，结果按映射拼出")
    void executesDeclaredStepsInOrder() {
        TaskResult result = agent().execute(task(Map.of("payee", "张三", "amount", "1000")));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
        assertThat(result.resultPayload())
                .containsEntry("payee", "张三")
                .containsEntry("amount", "1000")
                .containsEntry("fromAccount", "6222***8821")
                .containsEntry("flowVersion", "1.0.0");
        assertThat(result.resultPayload().get("serialNo")).isNotNull();
        assertThat(log.calls())
                .as("顺序由声明决定，不由类的注入顺序或方法调用顺序决定")
                .containsExactly("payment.resolveDefaultAccount", "payment.checkLimit",
                        "payment.submitTransfer");
    }

    @Test
    @DisplayName("条件不成立的步骤根本不会被调度")
    void skipsStepWhenConditionFails() {
        // 小额：submit 返回 noticeRequired=false，notify 的 when 不成立
        TaskResult small = agent().execute(task(Map.of("payee", "张三", "amount", "1000")));
        assertThat(small.resultPayload())
                .as("跳过的步骤不该在结果里留下空字段")
                .doesNotContainKey("noticeId");
        assertThat(log.calls()).doesNotContain("payment.sendNotice");
    }

    @Test
    @DisplayName("条件成立时该步执行，产出进结果")
    void runsConditionalStepWhenEligible() {
        TaskResult large = agent().execute(task(Map.of("payee", "李四", "amount", "20000")));

        assertThat(large.status()).isEqualTo(Enums.TaskStatus.SUCCESS);
        assertThat(large.resultPayload().get("noticeId")).isNotNull();
        assertThat(log.calls()).endsWith("payment.sendNotice");
    }

    @Test
    @DisplayName("没有幂等键就不办，且不回传凭据")
    void refusesTaskWithoutIdempotencyKey() {
        UnifiedTask noKey = new UnifiedTask(
                "task-" + UUID.randomUUID(), "trace", Enums.TaskSource.FAST_PATH, "转账",
                "cap.transfer", Map.of("payee", "张三", "amount", "1000"), RiskLevel.R2,
                Map.of(), GuardrailCheck.pending(), null, List.of(), null);

        TaskResult result = agent().execute(noKey);

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.FATAL);
        assertThat(result.resultPayload()).containsEntry("error", "MISSING_IDEMPOTENCY_KEY");
        assertThat(result.idempotencyKey()).isNull();
        assertThat(log.calls()).as("一个操作都不该被调到").isEmpty();
    }

    @Test
    @DisplayName("业务规则拒绝归 FATAL，不给重放资格")
    void businessRejectionIsFatal() {
        TaskResult result = agent().execute(task(Map.of("payee", "张三", "amount", "60000")));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.FATAL);
        assertThat(log.calls()).doesNotContain("payment.submitTransfer");
    }

    @Test
    @DisplayName("操作抛普通异常时，按声明的 onError 归类")
    void undeclaredExceptionFollowsDeclaredClassification() {
        // account 那步在声明里写的是 RETRYABLE，操作抛的是一个没有归类的运行时异常
        List<DomainOperation> operations = List.of(
                new StubOperations.ResolveDefaultAccount(log, new IllegalStateException("核心系统超时")),
                new StubOperations.CheckLimit(log, LIMIT),
                new StubOperations.SubmitTransfer(log, NOTICE_THRESHOLD),
                new StubOperations.SendNotice(log));

        TaskResult result = agent(operations).execute(task(Map.of("payee", "张三", "amount", "1000")));

        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
    }

    @Test
    @DisplayName("缺信息回 NEED_USER，由中控去问用户，不在领域侧挂起")
    void needUserComesBackAsResultNotSuspension() {
        List<DomainOperation> operations = List.of(
                new StubOperations.ResolveDefaultAccount(log),
                new StubOperations.CheckLimit(log, LIMIT),
                new NeedUserSubmit(),
                new StubOperations.SendNotice(log));

        TaskResult result = agent(operations).execute(task(Map.of("payee", "张三", "amount", "1000")));

        assertThat(result.status()).isEqualTo(Enums.TaskStatus.NEED_USER);
        assertThat(result.failureClass()).isEqualTo(Enums.FailureClass.NEED_USER);
        assertThat(result.idempotencyKey())
                .as("凭据要回传，否则中控续轮时无从判断这笔业务已经发过键")
                .isNotNull();
    }

    @Test
    @DisplayName("同一把幂等键重投，下游只被写一次")
    void replayDoesNotSubmitTwice() {
        StubOperations.SubmitTransfer submit = new StubOperations.SubmitTransfer(log, NOTICE_THRESHOLD);
        WorkflowDomainAgent agent = agent(List.of(
                new StubOperations.ResolveDefaultAccount(log),
                new StubOperations.CheckLimit(log, LIMIT), submit,
                new StubOperations.SendNotice(log)));

        UnifiedTask task = task(Map.of("payee", "张三", "amount", "1000"));
        TaskResult first = agent.execute(task);
        TaskResult second = agent.execute(task);

        assertThat(second.resultPayload()).isEqualTo(first.resultPayload());
        assertThat(submit.submissions())
                .as("流程会被完整跑第二遍，幂等必须落在写操作上——这也是为什么幂等键要交到操作手里")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不承接的能力如实回答")
    void supportsOnlyDeclaredCapabilities() {
        WorkflowDomainAgent agent = agent();
        assertThat(agent.supports("cap.transfer")).isTrue();
        assertThat(agent.supports("cap.bill.query")).isFalse();
        assertThat(agent.supports(null)).isFalse();
        assertThat(agent.loadedFlows()).containsOnlyKeys("cap.transfer");
    }

    /** 提交环节发现收款人不唯一。 */
    private static final class NeedUserSubmit implements DomainOperation {

        @Override
        public String name() {
            return "payment.submitTransfer";
        }

        @Override
        public Map<String, Object> execute(OperationContext ctx) {
            throw StepFailure.needUser("收款人有多个同名账户，需用户指定");
        }
    }

    private static UnifiedTask task(Map<String, Object> params) {
        return new UnifiedTask(
                "task-" + UUID.randomUUID(), "trace-" + UUID.randomUUID(), Enums.TaskSource.FAST_PATH,
                "转账", "cap.transfer", params, RiskLevel.R2,
                Map.of("confirmedBy", "u-1"), GuardrailCheck.passed(),
                "idem-" + UUID.randomUUID(), List.of(), null);
    }
}
