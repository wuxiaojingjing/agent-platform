package com.huawei.finance.domain.wealthproduct;
public interface WealthProductPort {
    ProductView product(String capabilityId);
    record ProductView(String productCode, String name, String domain, String riskLevel, String returnRate, String term) { }
}
