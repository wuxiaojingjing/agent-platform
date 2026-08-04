package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DomainAgent} 的契约测试包。行内实现完之后继承本类，跑绿即算合规。
 *
 * <pre>{@code
 * class MyTransferAgentContractTest extends DomainAgentContract {
 *     protected DomainAgent agent() { return new MyTransferAgent(...); }
 *     protected String capabilityId() { return "cap.transfer"; }
 *     protected Map<String, Object> validParameters() { return Map.of("payee", "张三", "amount", "100"); }
 * }
 * }</pre>
 *
 * <p><b>为什么要有这个东西。</b>{@code DomainAgent} 只有两个方法，看起来简单到不需要测。
 * 但它承载的约束不在方法签名里，而在中控对它的**假设**里：中控依据幂等键判定重放、
 * 依据 {@code executable()} 判定凭据、依据 {@code taskId} 对账。这些假设写在文档里，
 * 谁都会点头，然后各家实现各违反一条——而违反的后果是重复转账、无凭据执行、
 * 对不上账，全都不会在行内自己的单测里暴露，因为他们测的是自己的业务逻辑。
 *
 * <p>本类只测契约，不测业务。「转账扣款对不对」是行内自己的事，
 * 「同一个幂等键来两次会不会扣两次」是这里的事。
 */
public abstract class DomainAgentContract {

    /** 被测实现。每个用例都会调一次，实现方可以每次返回新实例，也可以返回同一个。 */
    protected abstract DomainAgent agent();

    /** 被测实现承接的一个能力标识。 */
    protected abstract String capabilityId();

    /** 一组能让该能力正常执行的业务参数。 */
    protected abstract Map<String, Object> validParameters();

    /**
     * 一个该实现**不**承接的能力标识。默认给一个不可能存在的值，
     * 行内若有更贴切的（比如隔壁领域的真实能力）可以覆盖。
     */
    protected String unsupportedCapabilityId() {
        return "cap.tck.definitely.not.supported";
    }

    @Test
    @DisplayName("supports 是纯函数：同样的入参问两次，答案必须一样")
    void supportsIsStable() {
        DomainAgent agent = agent();
        assertThat(agent.supports(capabilityId()))
                .as("声明承接的能力，supports 必须为真，否则 AgentInvoker 永远路由不到它")
                .isTrue();
        assertThat(agent.supports(capabilityId())).isEqualTo(agent.supports(capabilityId()));
        assertThat(agent.supports(unsupportedCapabilityId())).isFalse();
    }

    @Test
    @DisplayName("supports 面对 null 不能抛异常")
    void supportsToleratesNull() {
        // AgentInvoker 会拿 task.capabilityId() 逐个问过去。若某个实现在这里抛异常，
        // 遭殃的不是它自己，是排在它后面的所有 Agent —— 整条链路直接中断，
        // 而异常栈里只会看到这个不相干的实现
        assertThatCode(() -> agent().supports(null)).doesNotThrowAnyException();
    }

    /**
     * 没有幂等键就不许执行。
     *
     * <p>幂等键是护栏通过后才签发的执行凭据（实施架构 §8.4）。没有它却执行了，
     * 等于绕过护栏动了真实业务——这是本系统最不能接受的一类缺陷，
     * 而且它在正常链路上永远不会触发，只有中控出 bug 或有人直接调 Agent 时才暴露。
     * 所以每个实现都得自己再查一次，不能依赖调用方。
     */
    @Test
    @DisplayName("拒绝执行没有幂等键的任务")
    void refusesTaskWithoutIdempotencyKey() {
        UnifiedTask noKey = task(null);
        assertThat(noKey.executable()).as("前置条件：构造出来的就该是不可执行的").isFalse();

        TaskResult result = agent().execute(noKey);
        assertThat(result).as("即便拒绝，也要返回结果而不是抛异常或返回 null").isNotNull();
        assertThat(result.success())
                .as("没有执行凭据却报成功，说明这次执行绕过了护栏")
                .isFalse();
    }

    @Test
    @DisplayName("返回的 taskId 与入参一致")
    void echoesTaskId() {
        UnifiedTask task = task(newKey());
        TaskResult result = agent().execute(task);
        assertThat(result).isNotNull();
        assertThat(result.taskId())
                .as("中控靠 taskId 对账。对不上时那条任务会一直停在 RUNNING，直到超时兜底")
                .isEqualTo(task.taskId());
    }

    /**
     * 同一个幂等键重放，结果必须一致。
     *
     * <p>这是整个 TCK 里最要紧的一条。网络重试、用户重复点击、中控故障恢复后重投，
     * 都会让同一个幂等键到达两次。第二次要么返回第一次的结果，要么明确报重复，
     * **但绝不能再执行一次真实业务**。
     *
     * <p>本用例只能验到「两次的成败与 taskId 一致」——「有没有真的扣两次钱」
     * 得由行内自己在实现侧断言，那需要看得到下游账务。这条守住的是最外层：
     * 连返回值都不一致的实现，一定没有做幂等。
     */
    @Test
    @DisplayName("同一任务重投，两次结果一致")
    void replayWithSameKeyIsIdempotent() {
        // 同一个 task 实例投两次，而不是用同一个幂等键构造两个新 task：
        // 后者的 taskId 天然不同，而 TaskResult 要回显入参 taskId，
        // 于是「两次结果一致」这句话在那种构造下根本不成立
        UnifiedTask task = task(newKey());
        DomainAgent agent = agent();

        TaskResult first = agent.execute(task);
        TaskResult second = agent.execute(task);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(second.success())
                .as("同一任务第二次执行的成败与第一次不同，说明去重没有以幂等键为准")
                .isEqualTo(first.success());
        assertThat(second.resultPayload())
                .as("同一幂等键两次返回的业务数据不同。要么第二次真的又执行了一遍，"
                        + "要么返回的不是第一次的结果——两种都不是幂等")
                .isEqualTo(first.resultPayload());
    }

    /**
     * 不同幂等键、相同业务参数，必须当成两笔。
     *
     * <p>这条和上一条是一对，缺了它上一条可以靠「永远拒绝第二次」作弊通过。
     * 更现实的是另一种错法：实现方按业务参数去重（收款人 + 金额相同就当重复）。
     * 那在「给同一个人连转两笔一样的钱」这个完全正常的场景下会吞掉第二笔，
     * 而用户看到的是「转账成功」——钱没动，提示却说动了。
     */
    @Test
    @DisplayName("不同幂等键、相同参数，不得当作重复吞掉")
    void differentKeysAreDifferentTransactions() {
        DomainAgent agent = agent();
        TaskResult first = agent.execute(task(newKey()));
        TaskResult second = agent.execute(task(newKey()));

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.success())
                .as("换了幂等键仍被当成重复，说明去重依据是业务参数而不是幂等键。"
                        + "用户给同一个人连转两笔一样的钱是正常场景，第二笔会被静默吞掉")
                .isEqualTo(first.success());
        assertThat(second.taskId()).isNotEqualTo(first.taskId());
    }

    @Test
    @DisplayName("execute 不抛异常，失败也走 TaskResult")
    void failuresComeBackAsResults() {
        // 抛出去的异常会被 AgentInvoker 归成 FATAL，丢掉实现方本可以提供的
        // failureClass 与业务错误码 —— 到了面客那一层就只剩一句通用兜底话术
        assertThatCode(() -> agent().execute(task(newKey()))).doesNotThrowAnyException();
    }

    private UnifiedTask task(String idempotencyKey) {
        boolean passed = idempotencyKey != null;
        return new UnifiedTask(
                "tck-" + UUID.randomUUID(),
                "tck-trace-" + UUID.randomUUID(),
                Enums.TaskSource.FAST_PATH,
                "TCK 契约用例",
                capabilityId(),
                validParameters(),
                RiskLevel.R1,
                Map.of(),
                // 幂等键非空时护栏必须已通过，否则 UnifiedTask 的构造断言会拒绝
                passed ? GuardrailCheck.passed() : GuardrailCheck.pending(),
                idempotencyKey,
                List.of(),
                null);
    }

    private static String newKey() {
        return "tck-idem-" + UUID.randomUUID();
    }
}
