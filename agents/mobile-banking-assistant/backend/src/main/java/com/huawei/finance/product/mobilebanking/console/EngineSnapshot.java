package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.response.ResponsePlanner;
import com.huawei.finance.response.ResponseRealizer;

/**
 * 一次请求所依据的那一整套资产与由它装出来的组件。
 *
 * <p>四样东西必须同版本。分别去取的后果是：仲裁按新阈值判出了一个新出口，渲染却用旧模板
 * 找不到对应的键，于是走兜底话术——用户看到的是一句「暂时无法处理」，而每一层的日志
 * 都显示自己没错。这种不一致只在重载那一瞬间的在途请求上出现，事后无法复现。
 */
public record EngineSnapshot(AssetBundle bundle, IntentEngine intentEngine,
                             ResponsePlanner planner, ResponseRealizer renderer) {
}
