package com.huawei.finance.domain.account;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ContextResolutionMarkers;
import com.huawei.finance.contracts.model.SlotNames;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 账户管理域叶子执行器（阶段 3a）。
 *
 * <p>从 {@code MockAccountAgent} 上收：账户主路径不再依赖 mock 开关才能应答。
 * 演示数据仍是夹具级假余额——生产须换成行内账户视图服务；契约形状不变。
 */
public class AccountDomainAgent implements TechDomainAgent {

    public static final String REFERENCE_RESOLUTION_CAPABILITY = "cap.account.reference.resolve";

    private final AccountPort port;

    public AccountDomainAgent(AccountPort port) {
        this.port = port;
    }

    private static final Set<String> SUPPORTED = Set.of(
            REFERENCE_RESOLUTION_CAPABILITY,
            "cap.account.balance.query",
            "cap.account.transaction.query",
            "cap.payroll.arrival.query",
            "cap.account.card.status.query");

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
            return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, Enums.FailureClass.FATAL,
                    Map.of("error", "MISSING_IDEMPOTENCY_KEY"), null, task.guardrailCheck());
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            String principal = principal(task);
            if (principal == null) {
                return needPrincipal(task);
            }
            if (REFERENCE_RESOLUTION_CAPABILITY.equals(task.capabilityId())) {
                return resolveReference(task, principal);
            } else if ("cap.account.transaction.query".equals(task.capabilityId())) {
                var rows = port.transactions(principal);
                payload.put("transactions", rows);
                if (!rows.isEmpty()) payload.put("accountAlias", "PRIMARY");
            } else if ("cap.payroll.arrival.query".equals(task.capabilityId())) {
                var payroll = port.transactions(principal).stream()
                        .filter(row -> row.description() != null && row.description().contains("工资"))
                        .toList();
                payload.put("payrollArrived", !payroll.isEmpty());
                payload.put("payrollTransactions", payroll);
            } else if ("cap.account.card.status.query".equals(task.capabilityId())) {
                var cards = port.accountView(principal).cards();
                if (cards.isEmpty()) return failed(task, Enums.FailureClass.FATAL, "ACCOUNT_NOT_FOUND");
                payload.put("accountAlias", cards.getFirst().alias());
                payload.put("cardStatus", "ACTIVE");
            } else {
                var cards = port.accountView(principal).cards();
                if (cards.isEmpty()) return failed(task, Enums.FailureClass.FATAL, "ACCOUNT_NOT_FOUND");
                AccountPort.CardView selected = selectedCard(task, cards);
                if (selected == null) return failed(task, Enums.FailureClass.FATAL, "ACCOUNT_REFERENCE_STALE");
                payload.put("cards", cards);
                payload.put("accountAlias", selected.alias());
                payload.put("availableBalance", selected.availableBalance());
            }
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    payload, task.idempotencyKey(), task.guardrailCheck());
        } catch (org.springframework.web.client.ResourceAccessException
                 | org.springframework.web.client.RestClientResponseException e) {
            return failed(task, Enums.FailureClass.RETRYABLE, "ACCOUNT_BACKEND_UNAVAILABLE");
        }
    }

    private TaskResult resolveReference(UnifiedTask task, String principal) {
        var cards = port.accountView(principal).cards();
        AccountPort.CardView selected = selectedCard(task, cards);
        if (selected == null) {
            return failed(task, Enums.FailureClass.FATAL, "ACCOUNT_REFERENCE_STALE");
        }
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(SlotNames.ACCOUNT_ORDINAL, selected.index());
        slots.put(SlotNames.FROM_ACCOUNT, selected.alias());
        Object basis = task.parameters().get(SlotNames.AMOUNT_BASIS);
        boolean refresh = AccountReferenceResolver.REQUERY_THEN_HALF.equals(basis);
        if (refresh) {
            slots.put(SlotNames.AMOUNT_BASIS, basis);
            if (ContextResolutionMarkers.EXECUTION.equals(
                    task.parameters().get(ContextResolutionMarkers.RESOLUTION_MODE))) {
                String amount = half(selected.availableBalance());
                if (amount == null) {
                    return failed(task, Enums.FailureClass.FATAL, "BALANCE_REQUERY_INVALID");
                }
                slots.put(SlotNames.AMOUNT, amount);
            }
        }
        return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of(ContextResolutionMarkers.RESOLVED_SLOTS, Map.copyOf(slots),
                        "refreshAtExecution", refresh),
                task.idempotencyKey(), task.guardrailCheck());
    }

    private static String half(String raw) {
        if (raw == null) return null;
        try {
            BigDecimal value = new BigDecimal(raw.replace(",", "").trim());
            BigDecimal half = value.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            DecimalFormat format = new DecimalFormat("0.##");
            format.setRoundingMode(RoundingMode.HALF_UP);
            return format.format(half);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String principal(UnifiedTask task) {
        Object value = task.parameters().get("principalRef");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static AccountPort.CardView selectedCard(
            UnifiedTask task, java.util.List<AccountPort.CardView> cards) {
        Object ordinal = task.parameters().get("accountOrdinal");
        if (ordinal == null) return cards.getFirst();
        try {
            int index = Integer.parseInt(String.valueOf(ordinal));
            return cards.stream().filter(card -> card.index() == index).findFirst().orElse(null);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static TaskResult needPrincipal(UnifiedTask task) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.NEED_USER, Enums.FailureClass.NEED_USER,
                Map.of("missingSlots", java.util.List.of("principalRef"), "reasonCode", "PRINCIPAL_REQUIRED"),
                task.idempotencyKey(), task.guardrailCheck());
    }

    private static TaskResult failed(UnifiedTask task, Enums.FailureClass failure, String reason) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, failure,
                Map.of("reasonCode", reason), task.idempotencyKey(), task.guardrailCheck());
    }
}
