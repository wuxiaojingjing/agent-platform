package com.huawei.finance.domain.finance;

import java.util.Map;
import java.util.Set;

/** 版本化菜单资产边界。 */
public interface NavigationCatalogPort {
    Map<String, Object> find(String capabilityId);
    Set<String> capabilities();
}
