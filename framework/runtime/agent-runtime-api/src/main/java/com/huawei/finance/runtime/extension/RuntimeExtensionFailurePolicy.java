package com.huawei.finance.runtime.extension;

import com.huawei.finance.stability.Api;

/** Agent Runtime 扩展失败后的收口方式。 */
@Api
public enum RuntimeExtensionFailurePolicy {
    FAIL_CLOSED,
    SKIP_AND_RECORD,
    FALLBACK_DEFAULT
}
