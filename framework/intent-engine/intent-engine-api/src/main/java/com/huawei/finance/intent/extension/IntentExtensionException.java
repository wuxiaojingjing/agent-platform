package com.huawei.finance.intent.extension;

import com.huawei.finance.stability.Api;

/** 意图扩展违反契约或按失败关闭策略退出。 */
@Api
public final class IntentExtensionException extends RuntimeException {

    public IntentExtensionException(String message) {
        super(message);
    }

    public IntentExtensionException(String message, Throwable cause) {
        super(message, cause);
    }
}
