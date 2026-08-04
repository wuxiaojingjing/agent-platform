package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Spi;

/** 将不透明主体引用解析为目标 Agent 本地主体；实现不得记录引用值。 */
@Spi
public interface PrincipalResolver {
    ResolvedPrincipal resolve(String tenantId, String targetAgentId, PrincipalContext context);
}
