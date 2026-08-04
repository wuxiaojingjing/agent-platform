package com.huawei.finance.response;

import java.util.List;
import java.util.Map;

/** User-visible context supplied only to response text realization. */
public record ResponseModelContext(
        List<Map<String, Object>> conversationHistory,
        String userQuery,
        Map<String, Object> committedFacts) {

    public ResponseModelContext {
        conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        userQuery = userQuery == null ? "" : userQuery;
        committedFacts = committedFacts == null ? Map.of() : Map.copyOf(committedFacts);
    }

    public static ResponseModelContext empty() {
        return new ResponseModelContext(List.of(), "", Map.of());
    }
}
