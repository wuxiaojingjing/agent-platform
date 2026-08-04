package com.huawei.finance.domain.insurance;
public interface InsuranceProductPort {
    ProductView product(String principalRef);
    record ProductView(String productCode, String name, String domain,
                       String riskLevel, String returnRate, String term) { }
}
