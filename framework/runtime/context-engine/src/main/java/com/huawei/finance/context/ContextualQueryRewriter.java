package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;

@FunctionalInterface
public interface ContextualQueryRewriter {
    ContextualQuery rewrite(String query, IntentContext context);
}
