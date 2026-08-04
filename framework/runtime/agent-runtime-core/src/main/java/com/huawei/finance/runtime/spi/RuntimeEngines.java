package com.huawei.finance.runtime.spi;

import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.response.ResponsePlanner;
import com.huawei.finance.response.ResponseRealizer;

/** 一轮请求所依据的同版本引擎快照（资产 / 意图 / 回复须同批）。 */
public record RuntimeEngines(
        AssetBundle bundle,
        IntentEngine intentEngine,
        ResponsePlanner planner,
        ResponseRealizer renderer) {
}
