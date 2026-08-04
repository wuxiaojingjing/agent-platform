package com.huawei.finance.response;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 回复渲染的展示层默认值。 */
@ConfigurationProperties(prefix = "huawei.finance.agent.response")
public class ResponseProperties {

    /**
     * 槽位里没带币种时，渲染用的默认币种符号。
     *
     * <p>放在配置而不是资产包：资产是随话术一起版本化、可热更的东西，而币种符号取决于
     * 这套系统装在哪家行、面向哪个地区，一次定死，不会跟着话术改版走。
     *
     * <p>之所以要有默认值而不是缺了就报错：模板的变量 Schema 把 {@code currency} 列为必填，
     * 而领域 Agent 返回的 payload 未必带它。缺变量时 Freemarker 会渲染出空白，
     * 在面客链路上表现为「金额前面什么都没有」——比直接报错更危险。
     */
    private String defaultCurrency = "¥";

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }
}
