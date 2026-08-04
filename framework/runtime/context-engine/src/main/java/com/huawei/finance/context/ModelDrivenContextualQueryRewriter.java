package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import java.time.Instant;

/**
 * Delegates natural-language context understanding to the configured model and enforces the
 * deterministic context boundary on its output.
 */
public final class ModelDrivenContextualQueryRewriter implements ContextualQueryRewriter {

    private final ContextualQueryModel model;
    private final ContextRewritePolicyGate policy;

    public ModelDrivenContextualQueryRewriter(ContextualQueryModel model) {
        this(model, new ContextRewritePolicyGate());
    }

    ModelDrivenContextualQueryRewriter(ContextualQueryModel model, ContextRewritePolicyGate policy) {
        this.model = model;
        this.policy = policy;
    }

    @Override
    public ContextualQuery rewrite(String query, IntentContext context) {
        String original = query == null ? "" : query.trim();
        if (context == null || !context.usableAt(Instant.now()) || context.evidenceRefs().isEmpty()
                || model == null) {
            return ContextualQuery.identity(original, context == null ? -1 : context.stateVersion(),
                    context == null ? java.util.List.of() : context.evidenceRefs());
        }
        return policy.apply(original, context, model.rewrite(original, context));
    }
}
