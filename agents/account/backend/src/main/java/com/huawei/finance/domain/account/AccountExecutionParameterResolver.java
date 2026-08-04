package com.huawei.finance.domain.account;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.SlotNames;
import com.huawei.finance.contracts.port.ExecutionParameterResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Requeries the authoritative balance before a context-derived half-balance transfer executes. */
public final class AccountExecutionParameterResolver implements ExecutionParameterResolver {

    private final AccountPort accounts;

    public AccountExecutionParameterResolver(AccountPort accounts) {
        this.accounts = accounts;
    }

    @Override
    public Resolution resolve(String capabilityId, Map<String, Object> parameters,
                              ContextLease lease, String subjectRef) {
        if (!AccountReferenceResolver.REQUERY_THEN_HALF.equals(
                parameters.get(AccountReferenceResolver.AMOUNT_BASIS_SLOT))) {
            return Resolution.unchanged(parameters);
        }
        if (subjectRef == null || subjectRef.isBlank()) {
            return Resolution.failed(parameters, "PRINCIPAL_REQUIRED_FOR_BALANCE_REQUERY");
        }
        AccountPort.AccountView view;
        try {
            view = accounts.accountView(subjectRef);
        } catch (RuntimeException unavailable) {
            return Resolution.failed(parameters, "BALANCE_REQUERY_FAILED");
        }
        AccountPort.CardView card = select(view, parameters);
        if (card == null) {
            return Resolution.failed(parameters, "ACCOUNT_REFERENCE_STALE");
        }
        String amount = half(card.availableBalance());
        if (amount == null) {
            return Resolution.failed(parameters, "BALANCE_REQUERY_INVALID");
        }
        Map<String, Object> resolved = new LinkedHashMap<>(parameters);
        resolved.put(SlotNames.FROM_ACCOUNT, card.alias());
        resolved.put(SlotNames.AMOUNT, amount);
        return new Resolution(true, resolved, null,
                "requery:cap.account.balance.query:" + card.index());
    }

    private static AccountPort.CardView select(AccountPort.AccountView view,
                                               Map<String, Object> parameters) {
        if (view == null || view.cards().isEmpty()) return null;
        String alias = String.valueOf(parameters.getOrDefault(SlotNames.FROM_ACCOUNT, ""));
        for (AccountPort.CardView card : view.cards()) {
            if (!alias.isBlank() && alias.equals(card.alias())) return card;
        }
        Object ordinal = parameters.get(AccountReferenceResolver.ACCOUNT_ORDINAL_SLOT);
        if (ordinal != null) {
            try {
                int index = Integer.parseInt(String.valueOf(ordinal));
                return view.cards().stream().filter(card -> card.index() == index).findFirst().orElse(null);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String half(String raw) {
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
}
