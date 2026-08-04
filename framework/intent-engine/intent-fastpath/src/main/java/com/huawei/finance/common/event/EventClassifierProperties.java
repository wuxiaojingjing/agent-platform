package com.huawei.finance.common.event;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件分类规则配置。
 *
 * <p>v0.7 §3.2 要求权重、阈值与通道开关一律配置化；事件分类同理，词表写死在代码里就无法
 * 按版本发布与回归。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.event-classifier")
public class EventClassifierProperties {

    /** 规则版本，随词表变更递增，进 Trace 与评测记录。 */
    private String version = "event-rules-v1";

    /** 续轮短路的置信下限，低于此值一律回落完整快路径。 */
    private double shortCircuitThreshold = 0.75;

    private List<String> cancelKeywords =
            List.of("取消", "算了", "不办了", "不要了", "别转了", "不用了", "退出");

    private List<String> confirmKeywords =
            List.of("确认", "确定", "是的", "对", "好的", "同意", "可以", "继续", "执行");

    private List<String> correctionKeywords =
            List.of("不是", "不对", "错了", "改成", "应该是", "换成", "我说的是");

    private List<String> parallelMarkers = List.of("另外", "同时", "顺便", "还有", "再帮我");

    /** 被判为补充的短输入长度上限（字符数）。 */
    private int supplementMaxLength = 12;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public double getShortCircuitThreshold() {
        return shortCircuitThreshold;
    }

    public void setShortCircuitThreshold(double shortCircuitThreshold) {
        this.shortCircuitThreshold = shortCircuitThreshold;
    }

    public List<String> getCancelKeywords() {
        return cancelKeywords;
    }

    public void setCancelKeywords(List<String> cancelKeywords) {
        this.cancelKeywords = cancelKeywords;
    }

    public List<String> getConfirmKeywords() {
        return confirmKeywords;
    }

    public void setConfirmKeywords(List<String> confirmKeywords) {
        this.confirmKeywords = confirmKeywords;
    }

    public List<String> getCorrectionKeywords() {
        return correctionKeywords;
    }

    public void setCorrectionKeywords(List<String> correctionKeywords) {
        this.correctionKeywords = correctionKeywords;
    }

    public List<String> getParallelMarkers() {
        return parallelMarkers;
    }

    public void setParallelMarkers(List<String> parallelMarkers) {
        this.parallelMarkers = parallelMarkers;
    }

    public int getSupplementMaxLength() {
        return supplementMaxLength;
    }

    public void setSupplementMaxLength(int supplementMaxLength) {
        this.supplementMaxLength = supplementMaxLength;
    }
}
