package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 转账服务域 Mock 子 Agent。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至 {@code com.huawei.finance.domain.transfer.TransferDomainAgent}。
 *
 * <p>转账是本切片唯一的 R2 能力，因此这个桩额外承担一件事：证明「没有幂等键就执行不了」
 * 这条约束在真实调用链上是生效的，而不只是写在文档里。
 */
public class MockTransferAgent implements TechDomainAgent {

    private static final Set<String> SUPPORTED = Set.of("cap.transfer");

    @Override
    public String techDomainCode() {
        return "transfer";
    }

    @Override
    public String agentId() {
        return "agent.transfer";
    }

    @Override
    public boolean supports(String capabilityId) {
        return capabilityId != null && SUPPORTED.contains(capabilityId);
    }

    @Override
    public Set<String> advertisedCapabilities() {
        return SUPPORTED;
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) {
            return MockAgents.missingIdempotency(task);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payee", String.valueOf(task.parameters().getOrDefault("payee", "")));
        payload.put("amount", String.valueOf(task.parameters().getOrDefault("amount", "")));
        Object from = task.parameters().get("fromAccount");
        payload.put("fromAccount", from == null || String.valueOf(from).isBlank()
                ? "尾号 8821 借记卡" : String.valueOf(from));
        payload.put("serialNo", "TR" + task.idempotencyKey().substring(5, 15));
        payload.put("finishedAt", "刚刚");
        return MockAgents.success(task, payload);
    }
}
