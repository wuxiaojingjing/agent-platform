package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * 澄清话术配置（v0.7 §3.4）。
 *
 * <p>按槽位而非按能力组织：同一槽位在不同能力下的问法必须一致，
 * 按能力组织必然演化出多套措辞。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClarifyConfig {

    private static final String DEFAULT_REASON_KEY = "default";

    private Map<String, SlotClarify> slots = Map.of();
    private Exhausted exhausted = new Exhausted();
    private MultiTaskClarify multiTask = new MultiTaskClarify();
    private IntentChoiceClarify intentChoice = new IntentChoiceClarify();
    private Map<String, String> guardrailReasons = Map.of();

    /**
     * 把护栏码翻译成面客说法。
     *
     * <p>只看第一个码：护栏可能一次报出多条，但用户需要的是一个能理解的理由，
     * 不是一份问题清单。复合码（{@code MISSING_SLOT:payee}）先全码匹配，
     * 再退到冒号前的前缀，最后落到 default——任何情况下都不能把内部码露给用户。
     */
    public String guardrailReasonText(List<String> codes) {
        String fallback = guardrailReasons.getOrDefault(DEFAULT_REASON_KEY, "安全策略限制");
        if (codes == null || codes.isEmpty()) {
            return fallback;
        }
        String code = codes.get(0);
        String exact = guardrailReasons.get(code);
        if (exact != null) {
            return exact;
        }
        int colon = code.indexOf(':');
        if (colon > 0) {
            String prefixed = guardrailReasons.get(code.substring(0, colon));
            if (prefixed != null) {
                return prefixed;
            }
        }
        return fallback;
    }

    /** 单个槽位的澄清定义。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SlotClarify {
        private String question;
        private List<String> options = List.of();
        /** 用户回答 → 槽位取值。同时供事件分类器识别 SUPPLEMENT。 */
        private Map<String, String> valueMapping = Map.of();

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public List<String> getOptions() {
            return options;
        }

        public void setOptions(List<String> options) {
            this.options = options == null ? List.of() : List.copyOf(options);
        }

        public Map<String, String> getValueMapping() {
            return valueMapping;
        }

        public void setValueMapping(Map<String, String> valueMapping) {
            this.valueMapping = valueMapping == null ? Map.of() : Map.copyOf(valueMapping);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Exhausted {
        private String templateKey = "tpl.reject.clarify-exhausted";
        private String message = "";

        public String getTemplateKey() {
            return templateKey;
        }

        public void setTemplateKey(String v) {
            this.templateKey = v;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String v) {
            this.message = v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MultiTaskClarify {
        private String templateKey = "tpl.clarify.multi-task";
        private String question = "";

        public String getTemplateKey() {
            return templateKey;
        }

        public void setTemplateKey(String v) {
            this.templateKey = v;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String v) {
            this.question = v;
        }
    }

    /** 候选接近时展示的入口业务选择，不包含内部得分或候选 ID。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IntentChoiceClarify {
        private String templateKey = "tpl.clarify.slot";
        private String question = "请问您想办理哪项业务？";
        private int maxOptions = 3;

        public String getTemplateKey() {
            return templateKey;
        }

        public void setTemplateKey(String value) {
            templateKey = value;
        }

        public String getQuestion() {
            return question;
        }

        public void setQuestion(String value) {
            question = value;
        }

        public int getMaxOptions() {
            return maxOptions;
        }

        public void setMaxOptions(int value) {
            maxOptions = Math.max(1, value);
        }
    }

    public Map<String, SlotClarify> getSlots() {
        return slots;
    }

    public void setSlots(Map<String, SlotClarify> slots) {
        this.slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    public Exhausted getExhausted() {
        return exhausted;
    }

    public void setExhausted(Exhausted v) {
        this.exhausted = v;
    }

    public MultiTaskClarify getMultiTask() {
        return multiTask;
    }

    public void setMultiTask(MultiTaskClarify v) {
        this.multiTask = v;
    }

    public IntentChoiceClarify getIntentChoice() {
        return intentChoice;
    }

    public void setIntentChoice(IntentChoiceClarify value) {
        intentChoice = value == null ? new IntentChoiceClarify() : value;
    }

    public Map<String, String> getGuardrailReasons() {
        return guardrailReasons;
    }

    public void setGuardrailReasons(Map<String, String> v) {
        this.guardrailReasons = v == null ? Map.of() : Map.copyOf(v);
    }
}
