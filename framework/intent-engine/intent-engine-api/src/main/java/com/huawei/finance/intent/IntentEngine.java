package com.huawei.finance.intent;

import com.huawei.finance.stability.Api;

/**
 * 意图引擎门面：只产出意图与计划，不执行任务（架构草案 §4）。
 *
 * <p>实现可仍是快路径图；调用方不得绕过本接口再去依赖具体装配名。
 *
 * <p>标 {@code @Api} 而不是 {@code @Spi}：这是行内**调用**的入口，不是行内实现的扩展点。
 * 换引擎实现走的是替换整个模块，不是注册一个 Bean——{@code @Spi} 承诺的「不加抽象方法」
 * 在这里反而是错的约束，门面本就应当随引擎能力增长。
 */
@Api
public interface IntentEngine {

    IntentResult recognize(IntentRequest request);
}
