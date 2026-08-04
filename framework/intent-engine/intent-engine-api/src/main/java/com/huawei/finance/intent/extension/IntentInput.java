package com.huawei.finance.intent.extension;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.stability.Api;
import java.util.Map;
import java.util.Objects;

/**
 * 提供给意图流水线扩展的只读输入快照。
 *
 * <p>租户、Agent、会话和 trace 身份只通过 {@link RequestContext} 读取，不提供修改入口。
 */
@Api
public record IntentInput(
        RequestContext context,
        String originalQuery,
        String normalizedQuery,
        Map<String, Object> slots,
        Map<String, String> attributes) {

    public IntentInput {
        context = Objects.requireNonNull(context, "context");
        originalQuery = Objects.requireNonNullElse(originalQuery, "");
        normalizedQuery = Objects.requireNonNullElse(normalizedQuery, "");
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
