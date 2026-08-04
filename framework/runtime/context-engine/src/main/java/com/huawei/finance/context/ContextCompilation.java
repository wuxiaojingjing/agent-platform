package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.IntentContext;

/** ContextLease and rewrite projection compiled from one immutable history read. */
public record ContextCompilation(ContextLease lease, IntentContext intentContext) {
}
