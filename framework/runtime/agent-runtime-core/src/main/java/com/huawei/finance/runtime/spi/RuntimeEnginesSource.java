package com.huawei.finance.runtime.spi;

/** 提供当前请求可用的引擎快照（支持控制台热更换批）。 */
@FunctionalInterface
public interface RuntimeEnginesSource {

    RuntimeEngines current();
}
