package com.huawei.finance.domain.wealth;
public interface WealthPort {
    HoldingView holdings(String principalRef);
    record HoldingView(String totalAsset, String profit) { }
}
