package com.huawei.finance.fastpath;

import com.huawei.finance.common.event.EventClassification;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.intent.IntentResult;
import com.huawei.finance.intent.PathSummary;
import java.util.Map;
import java.util.Objects;

/**
 * 用快路径结果实现门面出参 {@link IntentResult}。
 *
 * <p>纯投影，不含业务判断。它存在的唯一理由是让 {@link FastPathResult}——引擎内部类型——
 * 不出现在依赖方能看到的任何签名上。本类刻意不 public：调用方只该拿到 {@code IntentResult}。
 *
 * <p>注意 {@link #originalQuery()} 与 {@link #normalizedQuery()} 只交两个字符串而不是整个
 * {@code RewriteResult}，理由见 {@code IntentResult#originalQuery()} 的说明——那也是门面模块
 * 能不依赖 HanLP 的原因。
 */
final class FastPathIntentResult implements IntentResult {

    private final FastPathResult raw;

    FastPathIntentResult(FastPathResult raw) {
        this.raw = Objects.requireNonNull(raw, "IntentResult.raw 不能为空");
    }

    @Override
    public RouteDecision decision() {
        return raw.intentPlan() == null ? raw.decision() : raw.decision().withIntentPlan(raw.intentPlan());
    }

    @Override
    public Map<String, Object> slots() {
        return raw.slots();
    }

    @Override
    public RecallResult recall() {
        return raw.recall();
    }

    @Override
    public String originalQuery() {
        return raw.rewrite().original();
    }

    @Override
    public String normalizedQuery() {
        return raw.rewrite().normalized();
    }

    @Override
    public EventClassification event() {
        return raw.event();
    }

    @Override
    public String templateKey() {
        return raw.templateKey();
    }

    @Override
    public IntentPlan intentPlan() {
        return raw.intentPlan();
    }

    @Override
    public PathSummary path() {
        return raw.path();
    }
}
