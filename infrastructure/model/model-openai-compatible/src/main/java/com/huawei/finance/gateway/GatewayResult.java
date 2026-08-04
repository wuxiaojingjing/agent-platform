package com.huawei.finance.gateway;

import com.huawei.finance.stability.Api;
import java.util.Optional;

/**
 * 模型网关调用结果。
 *
 * <p>刻意不用异常表达「模型不可用」。README 约束 6 把模型不可用定为**常态**：
 * 语义通道摘除与仲裁规则回退都是设计内的正常分支，用异常表达常态会诱导调用方
 * 写出 catch 空块，或者干脆让异常穿透到用户面前。返回值强制调用方处理不可用分支。
 *
 * @param value       成功时的返回值，失败时为 null
 * @param available   模型是否可用
 * @param reason      不可用原因，用于打点与排障
 * @param latencyMs   本次调用耗时，外部依赖预算单列（实施架构 §4.4 规则 3）
 */
@Api
public record GatewayResult<T>(T value, boolean available, String reason, long latencyMs) {

    public static <T> GatewayResult<T> ok(T value, long latencyMs) {
        return new GatewayResult<>(value, true, null, latencyMs);
    }

    public static <T> GatewayResult<T> unavailable(String reason, long latencyMs) {
        return new GatewayResult<>(null, false, reason, latencyMs);
    }

    public Optional<T> asOptional() {
        return available ? Optional.ofNullable(value) : Optional.empty();
    }

    public T orElse(T fallback) {
        return available && value != null ? value : fallback;
    }
}
