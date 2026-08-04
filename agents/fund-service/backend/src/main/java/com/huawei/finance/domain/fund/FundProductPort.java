package com.huawei.finance.domain.fund;
public interface FundProductPort {
    ProductView product(String principalRef);
    record ProductView(String productCode, String name, String domain,
                       String riskLevel, String returnRate, String term) { }
}
