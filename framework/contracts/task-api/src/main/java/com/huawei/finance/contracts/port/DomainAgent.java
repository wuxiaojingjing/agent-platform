package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Spi;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;

/**
 * 领域 Agent 执行端口（v0.7 §3.6）。
 *
 * <p>入参出参都是附录 B 的冻结契约，实现方因此不感知任务来自快路径还是慢路径。
 * 后续换成 OpenJiuwen Agent Server 时，替换的是实现类，不是这个接口。
 *
 * <p>实现方**不持有任务真值**：不得自行改写任务状态、不得自行决定重试。
 * 事务边界只有中控一个，两个地方都能改状态就必然会不一致。
 */
@Spi
public interface DomainAgent {

    /** 该 Agent 是否承接此能力。 */
    boolean supports(String capabilityId);

    /**
     * 对外声称承接的能力清单，仅用于清单展示与注册中心元数据。
     *
     * <p>**路由真值仍然是 {@link #supports}**，不是这个清单。两者可以不一致且不算错：
     * 按注册中心动态发现的实现，清单会随实例上下线变化，而 {@code supports} 是当下那一刻的判定。
     * 派单时若以清单为准，就会出现「清单里有、真派过去没人接」的空转。
     *
     * <p>默认空集：返回不了准确清单时就别猜。空集意味着「这个 Agent 不参与清单展示」，
     * 比列一份可能过期的清单诚实。
     */
    default java.util.Set<String> advertisedCapabilities() {
        return java.util.Set.of();
    }

    /**
     * 执行。
     *
     * <p>只有携带幂等键的任务才允许执行；实现方须以幂等键为准做去重，
     * 不得依据业务参数自行判断是否重复。
     */
    TaskResult execute(UnifiedTask task);
}
