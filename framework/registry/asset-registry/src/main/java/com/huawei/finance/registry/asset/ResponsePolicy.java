package com.huawei.finance.registry.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huawei.finance.contracts.model.Enums;
import java.util.ArrayList;
import java.util.List;

/** Versioned response realization policy loaded with the immutable asset snapshot. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsePolicy {

    private String version = "response-policy-v1";
    private String promptVersion = "response-realizer-v1";
    private String systemPrompt = "";
    private Rule defaults = new Rule();
    private List<Rule> rules = List.of();

    public Resolved resolve(String tenant, String agent, String scene, Enums.ResponsePhase phase) {
        Rule selected = defaults == null ? new Rule() : defaults;
        int selectedScore = -1;
        for (Rule rule : rules) {
            int score = rule.matchScore(tenant, agent, scene, phase);
            if (score > selectedScore) {
                selected = rule;
                selectedScore = score;
            }
        }
        return new Resolved(
                selected.getMode(), selected.getModel(), selected.getTemplateSet(),
                selected.getTemperature(), selected.getMaxTokens(), version, promptVersion, systemPrompt);
    }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = textOr(version, "response-policy-v1"); }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = textOr(promptVersion, "response-realizer-v1"); }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt == null ? "" : systemPrompt; }
    public Rule getDefaults() { return defaults; }
    public void setDefaults(Rule defaults) { this.defaults = defaults == null ? new Rule() : defaults; }
    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules == null ? List.of() : List.copyOf(new ArrayList<>(rules)); }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rule {
        private String tenant = "*";
        private String agent = "*";
        private String scene = "*";
        private String phase = "*";
        private Enums.RenderMode mode = Enums.RenderMode.TEMPLATE;
        private String model = "";
        private List<String> templateSet = List.of();
        private double temperature = 0.0;
        private int maxTokens = 256;

        int matchScore(String tenantValue, String agentValue, String sceneValue, Enums.ResponsePhase phaseValue) {
            int score = 0;
            score = match(tenant, tenantValue, score, 8); if (score < 0) return -1;
            score = match(agent, agentValue, score, 4); if (score < 0) return -1;
            score = match(scene, sceneValue, score, 2); if (score < 0) return -1;
            return match(phase, phaseValue == null ? null : phaseValue.name(), score, 1);
        }

        private static int match(String configured, String actual, int score, int weight) {
            if (configured == null || configured.isBlank() || "*".equals(configured)) return score;
            return configured.equals(actual) ? score + weight : -1;
        }

        public String getTenant() { return tenant; }
        public void setTenant(String tenant) { this.tenant = textOr(tenant, "*"); }
        public String getAgent() { return agent; }
        public void setAgent(String agent) { this.agent = textOr(agent, "*"); }
        public String getScene() { return scene; }
        public void setScene(String scene) { this.scene = textOr(scene, "*"); }
        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = textOr(phase, "*"); }
        public Enums.RenderMode getMode() { return mode; }
        public void setMode(Enums.RenderMode mode) { this.mode = mode == null ? Enums.RenderMode.TEMPLATE : mode; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model == null ? "" : model; }
        public List<String> getTemplateSet() { return templateSet; }
        public void setTemplateSet(List<String> templateSet) { this.templateSet = templateSet == null ? List.of() : List.copyOf(templateSet); }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = Math.max(32, maxTokens); }
    }

    public record Resolved(Enums.RenderMode mode, String model, List<String> templateSet,
                           double temperature, int maxTokens, String policyVersion,
                           String promptVersion, String systemPrompt) {
        public Resolved {
            mode = mode == null ? Enums.RenderMode.TEMPLATE : mode;
            model = model == null ? "" : model;
            templateSet = templateSet == null ? List.of() : List.copyOf(templateSet);
            systemPrompt = systemPrompt == null ? "" : systemPrompt;
        }
    }
}
