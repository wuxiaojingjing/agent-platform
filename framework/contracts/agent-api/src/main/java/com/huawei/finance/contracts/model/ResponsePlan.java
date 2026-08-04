package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;

/** 回复编排 → 渲染/融合回复层（v0.7 附录 B {@code ResponsePlan}，字段语义见 §3.7）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record ResponsePlan(
        String traceId,
        String taskId,
        String sceneCode,
        Enums.ResponsePhase responsePhase,
        String templateKey,
        String templateVersion,
        Enums.RenderMode renderMode,
        String responseModel,
        List<String> approvedTemplateKeys,
        Double responseTemperature,
        Integer responseMaxTokens,
        String responsePolicyVersion,
        String responsePromptVersion,
        Map<String, Object> slots,
        List<String> cardComponents,
        List<String> actionCodes,
        List<String> riskNoticeCodes,
        String channel,
        String locale,
        String fallbackTemplateKey) {

    public ResponsePlan {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        cardComponents = cardComponents == null ? List.of() : List.copyOf(cardComponents);
        actionCodes = actionCodes == null ? List.of() : List.copyOf(actionCodes);
        riskNoticeCodes = riskNoticeCodes == null ? List.of() : List.copyOf(riskNoticeCodes);
        renderMode = renderMode == null ? Enums.RenderMode.TEMPLATE : renderMode;
        approvedTemplateKeys = approvedTemplateKeys == null ? List.of() : List.copyOf(approvedTemplateKeys);
        responseTemperature = responseTemperature == null ? 0.0 : responseTemperature;
        responseMaxTokens = responseMaxTokens == null ? 256 : responseMaxTokens;
        locale = locale == null ? "zh-CN" : locale;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 字段多且构造点分散在四个出口，用 Builder 保证漏填的是 null 而不是错位的字符串。 */
    public static final class Builder {
        private String traceId;
        private String taskId;
        private String sceneCode;
        private Enums.ResponsePhase responsePhase;
        private String templateKey;
        private String templateVersion;
        private Enums.RenderMode renderMode = Enums.RenderMode.TEMPLATE;
        private String responseModel;
        private List<String> approvedTemplateKeys = List.of();
        private Double responseTemperature = 0.0;
        private Integer responseMaxTokens = 256;
        private String responsePolicyVersion;
        private String responsePromptVersion;
        private Map<String, Object> slots = Map.of();
        private List<String> cardComponents = List.of();
        private List<String> actionCodes = List.of();
        private List<String> riskNoticeCodes = List.of();
        private String channel;
        private String locale = "zh-CN";
        private String fallbackTemplateKey;

        public Builder traceId(String v) {
            this.traceId = v;
            return this;
        }

        public Builder taskId(String v) {
            this.taskId = v;
            return this;
        }

        public Builder sceneCode(String v) {
            this.sceneCode = v;
            return this;
        }

        public Builder responsePhase(Enums.ResponsePhase v) {
            this.responsePhase = v;
            return this;
        }

        public Builder templateKey(String v) {
            this.templateKey = v;
            return this;
        }

        public Builder templateVersion(String v) {
            this.templateVersion = v;
            return this;
        }

        public Builder renderMode(Enums.RenderMode v) {
            this.renderMode = v;
            return this;
        }

        public Builder responseModel(String v) { this.responseModel = v; return this; }
        public Builder approvedTemplateKeys(List<String> v) { this.approvedTemplateKeys = v; return this; }
        public Builder responseTemperature(Double v) { this.responseTemperature = v; return this; }
        public Builder responseMaxTokens(Integer v) { this.responseMaxTokens = v; return this; }
        public Builder responsePolicyVersion(String v) { this.responsePolicyVersion = v; return this; }
        public Builder responsePromptVersion(String v) { this.responsePromptVersion = v; return this; }

        public Builder slots(Map<String, Object> v) {
            this.slots = v;
            return this;
        }

        public Builder cardComponents(List<String> v) {
            this.cardComponents = v;
            return this;
        }

        public Builder actionCodes(List<String> v) {
            this.actionCodes = v;
            return this;
        }

        public Builder riskNoticeCodes(List<String> v) {
            this.riskNoticeCodes = v;
            return this;
        }

        public Builder channel(String v) {
            this.channel = v;
            return this;
        }

        public Builder locale(String v) {
            this.locale = v;
            return this;
        }

        public Builder fallbackTemplateKey(String v) {
            this.fallbackTemplateKey = v;
            return this;
        }

        public ResponsePlan build() {
            return new ResponsePlan(traceId, taskId, sceneCode, responsePhase, templateKey,
                    templateVersion, renderMode, responseModel, approvedTemplateKeys,
                    responseTemperature, responseMaxTokens, responsePolicyVersion, responsePromptVersion,
                    slots, cardComponents, actionCodes,
                    riskNoticeCodes, channel, locale, fallbackTemplateKey);
        }
    }
}
