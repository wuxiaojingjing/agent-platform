package com.huawei.finance.runtime.extension;

import com.huawei.finance.stability.Spi;
import java.util.Map;

/**
 * 任务结算后、回复规划前的有序展示增强点。
 *
 * <p>实现注册为 Spring Bean 并通过 {@code @Order} 排序。返回值只用于渲染，不能改变任务事实。
 */
@Spi
public interface ResponseEnricher {

    Map<String, Object> enrich(ResponseEnrichmentContext context);

    default String extensionId() {
        return getClass().getName();
    }

    default RuntimeExtensionFailurePolicy failurePolicy() {
        return RuntimeExtensionFailurePolicy.SKIP_AND_RECORD;
    }
}
