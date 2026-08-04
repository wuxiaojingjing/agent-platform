package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rejects model/rule rewrites that cite unavailable state or alter the source-version boundary. */
public final class ContextRewritePolicyGate {

    public ContextualQuery apply(String original, IntentContext context, ContextualQuery proposed) {
        List<String> available = context == null ? List.of() : context.evidenceRefs();
        if (context == null || !context.usableAt(Instant.now()) || proposed == null) {
            return ContextualQuery.identity(original, context == null ? -1 : context.stateVersion(), available);
        }
        if (proposed.stateVersion() != context.stateVersion()) {
            return ContextualQuery.identity(original, context.stateVersion(), available);
        }
        Set<String> allowed = new HashSet<>(available);
        if (!allowed.containsAll(proposed.usedContextRefs())
                || !allowed.containsAll(proposed.invalidatedContextRefs())) {
            return ContextualQuery.unresolvedReference(
                    original, context.stateVersion(), available, proposed);
        }
        if (proposed.standaloneQuery() == null || proposed.standaloneQuery().isBlank()
                || proposed.standaloneQuery().length() > 512
                || proposed.confidence() < 0.70) {
            return ContextualQuery.identity(original, context.stateVersion(), available);
        }
        for (ContextualQuery.Resolution resolution : proposed.resolutions()) {
            if (!allowed.contains(resolution.contextRef())
                    || resolution.mention() == null || !original.contains(resolution.mention())) {
                return ContextualQuery.identity(original, context.stateVersion(), available);
            }
            if ("ORDINAL_REFERENCE".equals(resolution.resolutionType())) {
                Object ordinalValue = proposed.slotUpdates().get("accountOrdinal");
                if (!(ordinalValue instanceof Number ordinal) || ordinal.intValue() < 1
                        || ordinal.intValue() > orderedReferenceSize(context, resolution.contextRef())) {
                    return ContextualQuery.unresolvedReference(
                            original, context.stateVersion(), available, proposed);
                }
            }
            if ("REQUERY_THEN_HALF".equals(resolution.resolutionType())
                    && !"REQUERY_THEN_HALF".equals(proposed.slotUpdates().get("amountBasis"))) {
                return ContextualQuery.identity(original, context.stateVersion(), available);
            }
        }
        boolean ordinalResolution = proposed.resolutions().stream()
                .anyMatch(item -> "ORDINAL_REFERENCE".equals(item.resolutionType()));
        boolean amountBasisResolution = proposed.resolutions().stream()
                .anyMatch(item -> "REQUERY_THEN_HALF".equals(item.resolutionType()));
        for (var update : proposed.slotUpdates().entrySet()) {
            if ("accountOrdinal".equals(update.getKey())) {
                if (!ordinalResolution || !(update.getValue() instanceof Number number)
                        || number.intValue() < 1) {
                    return ContextualQuery.identity(original, context.stateVersion(), available);
                }
                continue;
            }
            if ("amountBasis".equals(update.getKey())) {
                if (!amountBasisResolution || !"REQUERY_THEN_HALF".equals(update.getValue())) {
                    return ContextualQuery.identity(original, context.stateVersion(), available);
                }
                continue;
            }
            String confirmedRef = "task.confirmed." + update.getKey();
            if (proposed.eventType() != ContextualQuery.EventType.CORRECTION
                    || !allowed.contains(confirmedRef)
                    || !proposed.invalidatedContextRefs().contains(confirmedRef)) {
                return ContextualQuery.identity(original, context.stateVersion(), available);
            }
        }
        return proposed;
    }

    private static int orderedReferenceSize(IntentContext context, String ref) {
        return context.evidence().stream()
                .filter(evidence -> evidence.ref().equals(ref))
                .map(evidence -> evidence.value().get("cards"))
                .filter(Collection.class::isInstance)
                .map(Collection.class::cast)
                .mapToInt(Collection::size)
                .findFirst()
                .orElse(0);
    }
}
