package com.huawei.finance.intent;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.event.ActiveTaskView;
import com.huawei.finance.stability.Api;
import java.util.Map;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;

/**
 * 意图引擎入参（架构草案 §3 门面）。
 *
 * <p>字段与快路径的 {@code FastPathRequest} 对齐；门面层用本类型，避免业务编排直接依赖引擎内部名。
 *
 * <p>两者之间的互转刻意**不在本类型上**。此前它是同包的两个包内可见方法
 * （{@code toFastPathRequest()} 与 {@code from(FastPathRequest)}）：门面与实现同处
 * {@code com.huawei.finance.fastpath} 时，靠包可见性挡住外人。门面搬进本模块后这条路断了，
 * 而断得正好——引擎内部类型不在本模块的 classpath 上，这两个签名根本写不出来。
 * 转换搬去了 {@code com.huawei.finance.fastpath.FastPathRequests}，在那边仍是包内可见，
 * 所以谁也没法拿着一个 {@code IntentRequest} 顺出引擎的内部对象。
 */
@Api
public record IntentRequest(
        RequestContext ctx,
        String query,
        ActiveTaskView activeTask,
        Map<String, String> attributes,
        ContextualQuery contextualQuery,
        IntentContext intentContext) {

    public IntentRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public IntentRequest(RequestContext ctx, String query, ActiveTaskView activeTask,
                         Map<String, String> attributes, ContextualQuery contextualQuery) {
        this(ctx, query, activeTask, attributes, contextualQuery, null);
    }

    public IntentRequest(RequestContext ctx, String query, ActiveTaskView activeTask,
                         Map<String, String> attributes) {
        this(ctx, query, activeTask, attributes, null, null);
    }
}
