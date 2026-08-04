package com.huawei.finance.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.registry.asset.ArbitrationSkill;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArbitrationSkillKnowledgeTest {

    private static Map<String, Object> admitted(String name) {
        return Map.ofEntries(
                Map.entry("name", name),
                Map.entry("activation", "EVALUATED_GAP"),
                Map.entry("content", "versioned domain terminology"),
                Map.entry("baselineModelHealthy", true),
                Map.entry("baselineModelVersion", "model-baseline-v1"),
                Map.entry("baselinePromptVersion", "prompt-baseline-v1"),
                Map.entry("failureCategory", ArbitrationSkill.MODEL_SEMANTIC_FAILURE),
                Map.entry("gapType", ArbitrationSkill.DOMAIN_KNOWLEDGE_GAP),
                Map.entry("failedEvaluationIds", List.of("eval-baseline-17")),
                Map.entry("failedParaphraseCaseIds", List.of("paraphrase-a", "paraphrase-b")),
                Map.entry("positiveRegressionCaseIds", List.of("case-positive-17")),
                Map.entry("negativeRegressionCaseIds", List.of("case-negative-17")));
    }

    @Test
    void onlyEvaluatedGapsWithPositiveAndNegativeRegressionsReachTheModel() {
        ArbitrationSkill skill = new ArbitrationSkill();
        Map<String, Object> eligible = admitted("evaluated-gap");
        Map<String, Object> speculative = new java.util.LinkedHashMap<>(admitted("speculative-example"));
        speculative.put("failedEvaluationIds", List.of());
        Map<String, Object> oneSided = new java.util.LinkedHashMap<>(admitted("missing-negative-regression"));
        oneSided.put("negativeRegressionCaseIds", List.of());
        skill.setExamples(List.of(speculative, eligible, oneSided));

        assertThat(skill.getEligibleKnowledgeExamples()).containsExactly(eligible);
    }

    @Test
    void draftNeverReachesTheModelEvenWhenItHasEvidenceFields() {
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setExamples(List.of(Map.of(
                "name", "draft",
                "activation", "DRAFT",
                "content", "not approved",
                "failedEvaluationIds", List.of("eval"),
                "positiveRegressionCaseIds", List.of("positive"),
                "negativeRegressionCaseIds", List.of("negative"))));

        assertThat(skill.getEligibleKnowledgeExamples()).isEmpty();
    }

    @Test
    void infrastructureContractPolicyAndRuntimeFailuresNeverBecomeKnowledge() {
        List<Map<String, Object>> failures = List.of(
                with(admitted("infra"), "failureCategory", "INFRA_UNAVAILABLE"),
                with(admitted("schema"), "failureCategory", "CONTRACT_INVALID"),
                with(admitted("policy"), "failureCategory", "POLICY_REJECTED"),
                with(admitted("runtime"), "failureCategory", "RUNTIME_UNRESOLVED"),
                with(admitted("unhealthy"), "baselineModelHealthy", false));
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setExamples(failures);

        assertThat(skill.getEligibleKnowledgeExamples()).isEmpty();
    }

    @Test
    void oneOffFailureAndGeneralModelCapabilityGapRemainInert() {
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setExamples(List.of(
                with(admitted("one-off"), "failedParaphraseCaseIds", List.of("only-one")),
                with(admitted("model-capability"), "gapType", "MODEL_CAPABILITY_GAP")));

        assertThat(skill.getEligibleKnowledgeExamples()).isEmpty();
    }

    @Test
    void metadataReproducibilityAndBothRegressionSidesAreAllRequired() {
        List<Map<String, Object>> incomplete = List.of(
                with(admitted("unhealthy"), "baselineModelHealthy", false),
                with(admitted("missing-model-version"), "baselineModelVersion", ""),
                with(admitted("missing-prompt-version"), "baselinePromptVersion", ""),
                with(admitted("missing-failed-eval"), "failedEvaluationIds", List.of()),
                with(admitted("duplicate-paraphrases"), "failedParaphraseCaseIds", List.of("same", "same")),
                with(admitted("missing-positive"), "positiveRegressionCaseIds", List.of()),
                with(admitted("missing-negative"), "negativeRegressionCaseIds", List.of()),
                with(admitted("missing-content"), "content", ""),
                with(admitted("missing-name"), "name", ""));
        ArbitrationSkill skill = new ArbitrationSkill();
        skill.setExamples(incomplete);

        assertThat(skill.getEligibleKnowledgeExamples()).isEmpty();
    }

    private static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }
}
