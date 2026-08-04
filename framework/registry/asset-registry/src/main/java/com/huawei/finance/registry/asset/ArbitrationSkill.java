package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 可版本化的仲裁提示词（v0.7 §3.3 Arbitration Skill）。
 *
 * <p>提示词作为资产而非代码常量：只有这样才能单独评测、单独回滚，
 * 并把版本号记进每一条 {@code RouteDecision.promptVersion}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArbitrationSkill {

    public static final String MODEL_SEMANTIC_FAILURE = "MODEL_SEMANTIC_FAILURE";
    public static final String DOMAIN_KNOWLEDGE_GAP = "DOMAIN_KNOWLEDGE_GAP";

    private String version = "arb-skill-v1";
    private String system = "";
    private String user = "";
    private List<Map<String, Object>> examples = List.of();

    /** 用 {@code {{key}}} 占位符渲染 user 模板。刻意不用 Freemarker：提示词里大量出现花括号。 */
    public String renderUser(Map<String, String> variables) {
        String result = user;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    /** Versioned few-shot/domain knowledge; consumers decide how to project it into the prompt. */
    public List<Map<String, Object>> getExamples() {
        return examples;
    }

    public void setExamples(List<Map<String, Object>> examples) {
        this.examples = examples == null ? List.of() : List.copyOf(examples);
    }

    /**
     * Returns only knowledge supplements backed by a reproducible baseline-model gap and
     * positive/negative regression cases. Incomplete examples remain inert asset drafts.
     */
    public List<Map<String, Object>> getEligibleKnowledgeExamples() {
        return examples.stream()
                .filter(example -> "EVALUATED_GAP".equals(example.get("activation")))
                .filter(example -> knowledgeAdmissionErrors(example).isEmpty())
                .toList();
    }

    static List<String> knowledgeAdmissionErrors(Map<String, Object> example) {
        List<String> errors = new ArrayList<>();
        if (example == null) {
            return List.of("example is null");
        }
        String activation = stringValue(example.get("activation"));
        if (!"DRAFT".equals(activation) && !"EVALUATED_GAP".equals(activation)) {
            errors.add("activation must be DRAFT or EVALUATED_GAP");
            return List.copyOf(errors);
        }
        if ("DRAFT".equals(activation)) {
            return List.of();
        }
        if (stringValue(example.get("name")).isBlank()) {
            errors.add("name is required");
        }
        boolean hasContent = !stringValue(example.get("content")).isBlank()
                || (!stringValue(example.get("input")).isBlank()
                    && !stringValue(example.get("outputContract")).isBlank());
        if (!hasContent) {
            errors.add("content or input/outputContract is required");
        }
        if (!Boolean.TRUE.equals(example.get("baselineModelHealthy"))) {
            errors.add("baselineModelHealthy must be true");
        }
        if (stringValue(example.get("baselineModelVersion")).isBlank()) {
            errors.add("baselineModelVersion is required");
        }
        if (stringValue(example.get("baselinePromptVersion")).isBlank()) {
            errors.add("baselinePromptVersion is required");
        }
        if (!MODEL_SEMANTIC_FAILURE.equals(stringValue(example.get("failureCategory")))) {
            errors.add("failureCategory must be MODEL_SEMANTIC_FAILURE");
        }
        if (!DOMAIN_KNOWLEDGE_GAP.equals(stringValue(example.get("gapType")))) {
            errors.add("gapType must be DOMAIN_KNOWLEDGE_GAP");
        }
        requireEvidence(example, "failedEvaluationIds", errors);
        requireDistinctEvidence(example, "failedParaphraseCaseIds", 2, errors);
        requireEvidence(example, "positiveRegressionCaseIds", errors);
        requireEvidence(example, "negativeRegressionCaseIds", errors);
        return List.copyOf(errors);
    }

    private static void requireEvidence(Map<String, Object> example, String field,
                                        List<String> errors) {
        Object value = example.get(field);
        if (!(value instanceof Collection<?> collection)
                || collection.stream().map(ArbitrationSkill::stringValue).noneMatch(v -> !v.isBlank())) {
            errors.add(field + " must contain at least one id");
        }
    }

    private static void requireDistinctEvidence(Map<String, Object> example, String field,
                                                int minimum, List<String> errors) {
        Object value = example.get(field);
        if (!(value instanceof Collection<?> collection)
                || collection.stream().map(ArbitrationSkill::stringValue)
                        .filter(v -> !v.isBlank()).distinct().count() < minimum) {
            errors.add(field + " must contain at least " + minimum + " distinct ids");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
