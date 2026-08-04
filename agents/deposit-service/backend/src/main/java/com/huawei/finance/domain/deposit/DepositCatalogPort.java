package com.huawei.finance.domain.deposit;

public interface DepositCatalogPort {
    ProductView featuredProduct();
    record ProductView(String productCode, String name, String domain,
                       String riskLevel, String returnRate, String term) { }
}
