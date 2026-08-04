package com.huawei.finance.intent.extension;

import com.huawei.finance.stability.Spi;

/**
 * 召回后、仲裁前的有序扩展点。
 *
 * <p>业务实现注册为 Spring Bean，并使用 {@code @Order} 声明顺序。普通实现只应过滤或重排
 * 已召回候选；平台会拒绝新增候选和篡改候选的领域、槽位、风险或证据事实。
 */
@Spi
public interface CandidatePostProcessor {

    CandidateSet process(IntentInput input, CandidateSet candidates);

    /** 稳定的扩展标识，用于日志、指标和审计。 */
    default String extensionId() {
        return getClass().getName();
    }

    /** 安全扩展默认失败关闭；展示或弱增强类扩展可显式选择其他策略。 */
    default ExtensionFailurePolicy failurePolicy() {
        return ExtensionFailurePolicy.FAIL_CLOSED;
    }
}
