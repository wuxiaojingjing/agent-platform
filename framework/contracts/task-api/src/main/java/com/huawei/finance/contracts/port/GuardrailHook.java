package com.huawei.finance.contracts.port;

import com.huawei.finance.stability.Spi;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.UnifiedTask;

/**
 * 护栏钩子（v0.7 §8、实施架构 §8.4）。
 *
 * <p>本切片是策略桩，真实实现接权限中心与风控。接口先定下来，是因为幂等键的发放时机
 * 绑死在「护栏返回 PASSED 之后」——这个顺序不能等真实护栏到位再补。
 *
 * <p>入参是尚未发凭据的草稿任务：{@link UnifiedTask} 的构造器保证护栏未通过时
 * {@code idempotencyKey} 只能为空，护栏实现因此不可能拿到可执行凭据。
 */
@FunctionalInterface
@Spi
public interface GuardrailHook {

    GuardrailCheck check(UnifiedTask draft, CapabilityCard card);
}
