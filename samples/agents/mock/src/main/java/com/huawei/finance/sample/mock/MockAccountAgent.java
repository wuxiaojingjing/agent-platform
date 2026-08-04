package com.huawei.finance.sample.mock;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账户管理域 Mock 子 Agent。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至 {@code com.huawei.finance.domain.account.AccountDomainAgent}，
 * 不再由 {@link MockAgentConfiguration} 注册。
 */
public class MockAccountAgent implements TechDomainAgent {

    private static final Set<String> SUPPORTED = Set.of(
            "cap.account.balance.query",
            "cap.account.transaction.query");

    @Override
    public String techDomainCode() {
        return "account";
    }

    @Override
    public String agentId() {
        return "agent.account";
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
        if ("cap.account.transaction.query".equals(task.capabilityId())) {
            payload.put("accountAlias", "尾号 8821 借记卡");
            payload.put("transactions", "07-24 超市消费 -128.50；07-23 工资入账 +18,600.00；07-21 转账 -2,000.00");
        } else {
            List<Map<String, Object>> cards = orderedCards();
            payload.put("cards", cards);
            payload.put("accountAlias", cards.get(0).get("alias"));
            payload.put("availableBalance", cards.get(0).get("availableBalance"));
        }
        return MockAgents.success(task, payload);
    }

    private static List<Map<String, Object>> orderedCards() {
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(card(1, "尾号 8821 借记卡", "12,845.60"));
        cards.add(card(2, "尾号 3344 借记卡", "8,000.00"));
        cards.add(card(3, "尾号 5566 信用卡", "3,000.00"));
        return cards;
    }

    private static Map<String, Object> card(int index, String alias, String balance) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("alias", alias);
        row.put("availableBalance", balance);
        return row;
    }
}
