package com.huawei.finance.intent;

import com.huawei.finance.stability.Spi;

/**
 * 为一份已装配完成的平台默认引擎选择最终实现。
 *
 * <p>业务方可以返回包装后的默认引擎，也可以忽略参数并返回完整自定义实现。工厂在每次资产
 * 快照重建时调用，因此不会破坏 Agent 的资产热重载和单请求版本一致性。
 */
@Spi
public interface IntentEngineFactory {

    IntentEngine create(IntentEngine platformDefault);
}
