package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/** 能力风险等级。R1 及以上不因高置信跳过参数复核；R2 执行前必须显式确认（v0.7 §3.3）。 */
@Api
public enum RiskLevel {
    /** 只读查询类。 */
    R0,
    /** 有副作用但非资金变动。 */
    R1,
    /** 资金类交易，执行前必须显式确认。 */
    R2;

    public boolean requiresExplicitConfirmation() {
        return this == R2;
    }

    public boolean requiresSlotRecheck() {
        return this != R0;
    }
}
