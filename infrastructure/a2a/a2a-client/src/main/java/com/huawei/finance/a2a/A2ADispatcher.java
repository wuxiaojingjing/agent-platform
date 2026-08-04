package com.huawei.finance.a2a;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.stability.Spi;

/**
 * A2A 投递端口：客户端只依赖此接口，不依赖网关实现。
 *
 * <p>生产环境由 {@code a2a-client-starter} 装配远程 HTTP 实现，并固定投递到独立 Gateway App。
 * 进程内实现只由 {@code a2a-inprocess-testkit} 在测试范围装配。
 */
@Spi
public interface A2ADispatcher {

    DelegationReceipt dispatch(DelegationEnvelope envelope);
}
