package com.huawei.finance.sample.oj;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用的一个合规领域 Agent：按幂等键去重，无凭据拒绝执行。
 *
 * <p>这里不用 {@code mock} 里的那几个：那个模块只允许 {@code mobile-banking-assistant}
 * 依赖（{@code ModuleDependencyTest} 在守这条），而且它的 Mock 是为演示假数据造的，
 * 幂等语义不是它要表达的东西。本类刻意做到合规，好让 TCK 跑绿——
 * 用它验的是**这条链路**没有破坏契约，而不是 Agent 实现本身。
 */
public class StubTransferAgent implements DomainAgent {

    public static final String CAPABILITY = "cap.transfer";

    private final Map<String, TaskResult> executed = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String capabilityId) {
        return CAPABILITY.equals(capabilityId);
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) {
            return OjQueryCodec.failure(task, Enums.FailureClass.FATAL, "NOT_EXECUTABLE");
        }
        // 以幂等键为准去重，不看业务参数：同一个人连转两笔一样的钱是正常场景
        return executed.computeIfAbsent(task.idempotencyKey(), key -> new TaskResult(
                task.taskId(),
                Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE,
                Map.of("serialNo", "SN-" + key, "amount", String.valueOf(task.parameters().get("amount"))),
                key,
                task.guardrailCheck()));
    }
}
