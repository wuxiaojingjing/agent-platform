package com.huawei.finance.intent.cache;

import com.huawei.finance.stability.Spi;

/**
 * 出口决策缓存的运行控制面。
 *
 * <p>只控制 {@link DecisionCache}，不控制会话轮次、任务状态或 Runtime 持久化。
 */
@Spi
public interface DecisionCacheControl {

    boolean enabled();

    void setEnabled(boolean enabled);

    /** 删除当前版本的决策主键和浏览元数据，返回删除数量。 */
    long clear();
}
