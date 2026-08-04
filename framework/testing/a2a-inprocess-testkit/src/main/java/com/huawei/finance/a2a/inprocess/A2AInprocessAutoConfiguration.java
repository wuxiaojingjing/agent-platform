package com.huawei.finance.a2a.inprocess;

import com.huawei.finance.a2a.A2AConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 共 JVM / 本地演示：把 {@link A2AConfiguration} 拉进当前进程。
 *
 * <p>生产远程部署时 Agent 只依赖 {@code a2a-client}，不引本模块。
 */
@AutoConfiguration
@Import(A2AConfiguration.class)
public class A2AInprocessAutoConfiguration {
}
