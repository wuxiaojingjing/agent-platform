package com.huawei.finance.fastpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.event.ActiveTaskView;
import java.util.Map;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;

/**
 * 快路径入参。
 *
 * @param ctx             请求上下文（含 traceId、渠道、页面、是否澄清重试）
 * @param rawQuery        用户原文
 * @param activeTask      会话中的活跃任务，无则为 null
 * @param userStateValues 参与缓存键的用户状态取值
 */
public record FastPathRequest(
        RequestContext ctx,
        String rawQuery,
        ActiveTaskView activeTask,
        Map<String, String> userStateValues,
        ContextualQuery contextualQuery,
        IntentContext intentContext) {

    public FastPathRequest {
        userStateValues = userStateValues == null ? Map.of() : Map.copyOf(userStateValues);
    }

    public FastPathRequest(RequestContext ctx, String rawQuery, ActiveTaskView activeTask,
                           Map<String, String> userStateValues, ContextualQuery contextualQuery) {
        this(ctx, rawQuery, activeTask, userStateValues, contextualQuery, null);
    }

    public FastPathRequest(RequestContext ctx, String rawQuery, ActiveTaskView activeTask,
                           Map<String, String> userStateValues) {
        this(ctx, rawQuery, activeTask, userStateValues, null, null);
    }

    public int clarifyRounds() {
        return activeTask == null ? 0 : activeTask.clarifyRounds();
    }
}
