package com.huawei.finance.intent.bootstrap;

import com.huawei.finance.fastpath.FastPathAssembly;
import com.huawei.finance.fastpath.FastPathIntentEngine;
import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.intent.IntentEngineFactory;
import com.huawei.finance.registry.asset.AssetBundle;

/**
 * 意图引擎装配门面：Agent 应用经此创建快路径引擎，不直接依赖 fastpath 内部类型名。
 */
public final class IntentEngines {

    private IntentEngines() {
    }

    /** 与 {@link FastPathAssembly.Infrastructure} 同构，避免应用 import fastpath 包。 */
    public static final class Infrastructure {
        final FastPathAssembly.Infrastructure delegate;

        public Infrastructure(FastPathAssembly.Infrastructure delegate) {
            this.delegate = delegate;
        }
    }

    public static Infrastructure wrap(FastPathAssembly.Infrastructure infra) {
        return new Infrastructure(infra);
    }

    public static IntentEngine fastPath(AssetBundle bundle, Infrastructure infra) {
        return new FastPathIntentEngine(FastPathAssembly.build(bundle, infra.delegate));
    }

    /**
     * 创建平台默认引擎，再交给业务工厂包装或替换。
     *
     * <p>先创建默认引擎是为了让业务工厂可以选择复用；返回值不能为空。
     */
    public static IntentEngine fastPath(AssetBundle bundle, Infrastructure infra,
                                        IntentEngineFactory factory) {
        IntentEngine platformDefault = fastPath(bundle, infra);
        return select(platformDefault, factory);
    }

    static IntentEngine select(IntentEngine platformDefault, IntentEngineFactory factory) {
        IntentEngine selected = java.util.Objects.requireNonNull(factory, "factory")
                .create(java.util.Objects.requireNonNull(platformDefault, "platformDefault"));
        return java.util.Objects.requireNonNull(selected, "IntentEngineFactory 返回 null");
    }
}
