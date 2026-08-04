package com.huawei.finance.runtime.extension;

import com.huawei.finance.stability.Api;

/** Runtime 扩展按失败关闭策略中止本轮处理。 */
@Api
public final class AgentRuntimeExtensionException extends RuntimeException {

    public AgentRuntimeExtensionException(String message, Throwable cause) {
        super(message, cause);
    }
}
