package com.huawei.finance.runtime.loop;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class LoopActionValidator {
    private static final String CONFIRMED_SLOT = "CONFIRMED_SLOT";
    private static final String FACT_PREFIX = "FACT:";
    private final ContractValidator contracts;

    public LoopActionValidator() {
        this(new ContractValidator());
    }

    public LoopActionValidator(ContractValidator contracts) {
        this.contracts = Objects.requireNonNull(contracts, "contracts");
    }

    public Action validate(Action action, List<CapabilityCard> candidates, LoopContext context) {
        if (action == null || action.actionType() == null || blank(action.fingerprint())
                || blank(action.proposalReasonCode())) {
            throw new IllegalArgumentException("LOOP_ACTION_INVALID");
        }
        String expectedFingerprint = LoopActionFingerprint.of(
                action.actionType(), action.targetId(), action.parameters());
        if (!MessageDigest.isEqual(expectedFingerprint.getBytes(StandardCharsets.US_ASCII),
                action.fingerprint().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("LOOP_ACTION_FINGERPRINT_INVALID");
        }
        boolean targeted = action.actionType() == ActionType.CALL_CAPABILITY
                || action.actionType() == ActionType.DELEGATE_GOAL
                || action.actionType() == ActionType.RESOLVE_MENU;
        CapabilityCard card = candidates.stream()
                .filter(candidate -> Objects.equals(candidate.capabilityId(), action.targetId()))
                .findFirst().orElse(null);
        if (targeted && card == null) {
            throw new IllegalArgumentException("LOOP_TARGET_OUTSIDE_CANDIDATES");
        }
        if (!targeted && action.targetId() != null) {
            throw new IllegalArgumentException("LOOP_TARGET_NOT_ALLOWED");
        }
        if (!action.inputProvenance().keySet().equals(action.parameters().keySet())) {
            throw new IllegalArgumentException("LOOP_PARAMETER_PROVENANCE_MISSING");
        }
        for (Map.Entry<String, Object> parameter : action.parameters().entrySet()) {
            validateSource(parameter.getKey(), parameter.getValue(), action.inputProvenance().get(parameter.getKey()), context);
        }
        if (card != null) {
            validateDeclaredParameters(action.parameters(), card);
        } else if (!action.parameters().isEmpty()) {
            throw new IllegalArgumentException("LOOP_PARAMETERS_NOT_ALLOWED");
        }
        return action;
    }

    private void validateDeclaredParameters(Map<String, Object> parameters, CapabilityCard card) {
        if (!card.inputSchema().isEmpty()) {
            if (!contracts.validateSchema(card.inputSchema(), parameters).valid()) {
                throw new IllegalArgumentException("LOOP_PARAMETER_SCHEMA_INVALID");
            }
            return;
        }
        Set<String> declared = Set.copyOf(card.requiredSlots());
        if (!declared.containsAll(parameters.keySet())) {
            throw new IllegalArgumentException("LOOP_PARAMETER_NOT_DECLARED");
        }
    }

    private static void validateSource(String key, Object value, String source, LoopContext context) {
        if (CONFIRMED_SLOT.equals(source)) {
            if (!context.confirmedSlots().containsKey(key)
                    || !Objects.deepEquals(value, context.confirmedSlots().get(key))) {
                throw new IllegalArgumentException("LOOP_CONFIRMED_SLOT_MISMATCH");
            }
            return;
        }
        if (source != null && source.startsWith(FACT_PREFIX) && source.length() > FACT_PREFIX.length()) {
            Object fact = context.run().facts().get(source.substring(FACT_PREFIX.length()));
            if (fact instanceof Map<?, ?> factMap && factMap.containsKey(key)
                    && Objects.deepEquals(value, factMap.get(key))) {
                return;
            }
            throw new IllegalArgumentException("LOOP_DEPENDENCY_FACT_MISSING");
        }
        throw new IllegalArgumentException("LOOP_PARAMETER_PROVENANCE_INVALID");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
