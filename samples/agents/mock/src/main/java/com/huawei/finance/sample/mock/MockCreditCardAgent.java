package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 信用卡服务域 Mock 子 Agent。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至 {@code com.huawei.finance.domain.creditcard.CreditcardDomainAgent}。
 */
public class MockCreditCardAgent implements TechDomainAgent {

    private static final Set<String> SUPPORTED = Set.of(
            "cap.creditcard.bill.query",
            "cap.creditcard.repay",
            "cap.card.replace");

    @Override
    public String techDomainCode() {
        return "creditcard_service";
    }

    @Override
    public String agentId() {
        // 父卡历史 id；TOOLS 的 parentCapabilityId 仍指向 agent.creditcard
        return "agent.creditcard";
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
        switch (task.capabilityId()) {
            case "cap.creditcard.bill.query" -> {
                payload.put("billAmount", "3,280.00");
                payload.put("dueDate", "2026-08-10");
            }
            case "cap.creditcard.repay" -> {
                payload.put("amount", String.valueOf(task.parameters().getOrDefault("amount", "3,280.00")));
                payload.put("serialNo", "RP" + task.idempotencyKey().substring(5, 15));
            }
            default -> {
                payload.put("cardTypeName", cardTypeName(task.parameters().get("cardType")));
                payload.put("applicationNo", "RC" + task.idempotencyKey().substring(5, 15));
            }
        }
        return MockAgents.success(task, payload);
    }

    private static String cardTypeName(Object cardType) {
        return "DEBIT".equals(String.valueOf(cardType)) ? "借记卡" : "信用卡";
    }
}
