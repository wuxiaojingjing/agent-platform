package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

/**
 * 用户可见回复组件标识。
 *
 * <p>组件只决定展示形态；内容仍来自 {@link ResponsePlan#slots()}、结构化动作和 Runtime
 * Observation，不复制业务状态。
 */
@Api
public final class ResponseComponent {
    public static final String CHOICE_LIST = "CHOICE_LIST";
    public static final String TASK_PROGRESS = "TASK_PROGRESS";
    public static final String REVIEW_SUMMARY = "REVIEW_SUMMARY";
    public static final String RESULT_SUMMARY = "RESULT_SUMMARY";
    public static final String NAVIGATION = "NAVIGATION";
    public static final String LOOP_STATUS = "LOOP_STATUS";
    public static final String RISK_NOTICE = "RISK_NOTICE";
    public static final String PRODUCT_COMPARISON = "PRODUCT_COMPARISON";
    public static final String MENU_LIST = "MENU_LIST";

    private ResponseComponent() {
    }
}
