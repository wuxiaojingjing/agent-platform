package com.huawei.finance.fastpath;

import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.intent.IntentRequest;
import com.huawei.finance.intent.IntentResult;
import java.util.Objects;

/**
 * 用现有 {@link FastPathEngine} 实现 {@link IntentEngine}。
 *
 * <p>纯适配，不含业务判断——门面前后行为由 {@code IntentEngineFacadeTest} 与
 * {@code FastPathParityTest} 共同守住。
 */
public final class FastPathIntentEngine implements IntentEngine {

    private final FastPathEngine delegate;

    public FastPathIntentEngine(FastPathEngine delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public IntentResult recognize(IntentRequest request) {
        return new FastPathIntentResult(delegate.decide(FastPathRequests.toFastPathRequest(request)));
    }

    /** 测试与装配诊断用；业务编排不要依赖。 */
    public FastPathEngine delegate() {
        return delegate;
    }
}
