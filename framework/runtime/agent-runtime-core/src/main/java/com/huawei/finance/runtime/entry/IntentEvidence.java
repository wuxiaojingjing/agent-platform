package com.huawei.finance.runtime.entry;

import com.huawei.finance.contracts.model.*;
import java.util.Map;

public record IntentEvidence(String originalQuery,String normalizedQuery,RouteDecision proposal,
                             RecallResult recall,IntentPlan intentPlan,Map<String,Object> slots,String templateKey) {
    public IntentEvidence { slots=slots==null?Map.of():Map.copyOf(slots); }
}
