package com.huawei.finance.runtime.entry;

import com.huawei.finance.intent.IntentResult;

public class IntentEvidenceBuilder {
    public IntentEvidence build(IntentResult result){
        return new IntentEvidence(result.originalQuery(),result.normalizedQuery(),result.decision(),
                result.recall(),result.intentPlan(),result.slots(),result.templateKey());
    }
}
