package com.huawei.finance.intent.extension;

import com.huawei.finance.stability.Api;

/**
 * 意图扩展失败后的收口方式。
 *
 * <p>策略由扩展实现显式声明，平台不会把所有异常统一吞掉。
 */
@Api
public enum ExtensionFailurePolicy {

    /** 中止本次意图识别。适用于风险过滤、权限校验等安全扩展。 */
    FAIL_CLOSED,

    /** 跳过当前扩展，保留此前扩展已经产生的合法结果，并记录失败。 */
    SKIP_AND_RECORD,

    /** 放弃本阶段全部扩展结果，回到平台默认组件的原始结果。 */
    FALLBACK_DEFAULT
}
