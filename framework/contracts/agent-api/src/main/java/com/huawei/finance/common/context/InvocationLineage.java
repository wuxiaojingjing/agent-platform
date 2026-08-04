package com.huawei.finance.common.context;

import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;

/** A request's inherited cross-Agent task lineage. */
@Api
public record InvocationLineage(
        String rootTaskId,
        String parentTaskId,
        String sourceTaskId,
        List<String> delegationPath,
        Instant deadline) {

    public InvocationLineage {
        delegationPath = delegationPath == null ? List.of() : List.copyOf(delegationPath);
    }
}
