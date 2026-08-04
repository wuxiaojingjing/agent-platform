package com.huawei.finance.runtime.loop;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.EffectiveLoopAccess;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.orchestrator.loop.LoopContracts.Run;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public class LoopCandidateRetriever {
    public List<CapabilityCard> retrieve(RequestContext context, Run run, AssetBundle assets, int maxCandidates) {
        LinkedHashMap<String,CapabilityCard> selected = new LinkedHashMap<>();
        Set<String> domains = new LinkedHashSet<>();
        run.candidateIds().stream().map(assets::capability).filter(java.util.Objects::nonNull)
                .forEach(card -> {
                    selected.put(card.capabilityId(), card);
                    domains.addAll(card.domains());
                });
        if (!domains.isEmpty()) {
            assets.recallableCapabilities().stream()
                    .filter(card -> card.domains().stream().anyMatch(domains::contains))
                    .forEach(card -> selected.putIfAbsent(card.capabilityId(), card));
        }
        return selected.values().stream()
                .filter(c -> c.status() == Enums.CapabilityStatus.ACTIVE)
                .filter(c -> c.effectiveLoopAccess() != EffectiveLoopAccess.DENY)
                .filter(c -> !Boolean.TRUE.equals(c.principalRequired()) || context.principal().verified())
                .limit(maxCandidates).toList();
    }
}
