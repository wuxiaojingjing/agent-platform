package com.huawei.finance.sample.workflow;

import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.stability.Api;
import java.util.Map;

/**
 * 叶子操作能看到的全部输入。
 *
 * <p>刻意把整个 {@link UnifiedTask} 交出去而不是只给参数 Map：幂等键在任务上，
 * 而写操作把幂等键透传给下游是硬要求——下游只有拿到同一把键，才能在我们重放时
 * 认出这是同一笔业务。只给参数会让实现方没法拿到它，于是只能自己另造一个键，
 * 那把键与中控的对账口径就脱钩了。
 *
 * @param task  中控下发的任务，含幂等键、风险等级、截止时间
 * @param steps 在此之前各步的产出，键为步骤 id
 */
@Api
public record OperationContext(UnifiedTask task, Map<String, Object> steps) {

    public OperationContext {
        steps = steps == null ? Map.of() : Map.copyOf(steps);
    }

    /** 任务参数。等价于 {@code task().parameters()}，只是省一层。 */
    public Map<String, Object> params() {
        return task.parameters();
    }

    /**
     * 幂等键。写操作必须把它透传给下游。
     *
     * <p>不做空判断：能走到操作里的任务一定带键（{@code WorkflowDomainAgent} 在入口就把
     * 无键任务挡掉了），此处若为 null 说明入口那道检查被绕过了，让它当场炸掉比静默发一笔无凭据的交易好。
     */
    public String idempotencyKey() {
        return task.idempotencyKey();
    }

    /** 取某一步的某个产出字段，缺失返回 null。 */
    public Object step(String stepId, String field) {
        Object out = steps.get(stepId);
        return out instanceof Map<?, ?> map ? map.get(field) : null;
    }
}
