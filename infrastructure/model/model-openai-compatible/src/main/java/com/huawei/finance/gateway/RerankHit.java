package com.huawei.finance.gateway;

import com.huawei.finance.stability.Api;

/**
 * 重排结果项。
 *
 * @param index          在入参文档列表中的下标
 * @param relevanceScore 相关性分数
 */
@Api
public record RerankHit(int index, double relevanceScore) {
}
