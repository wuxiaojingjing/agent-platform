package com.huawei.finance.runtime.context;

import com.huawei.finance.context.ContextCompilation;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts;
import java.util.Optional;

/** One immutable read snapshot shared by continuation, rewrite, routing, execution and response. */
public record TurnContextSnapshot(
        Optional<ContinuationContracts.Context> continuation,
        ContextCompilation compilation) {
    public TurnContextSnapshot {
        continuation = continuation == null ? Optional.empty() : continuation;
    }
}
