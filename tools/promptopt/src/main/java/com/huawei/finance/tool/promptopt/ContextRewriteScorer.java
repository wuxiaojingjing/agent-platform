package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.databind.JsonNode;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scores context-rewrite prompts against frozen model inputs and policy invariants. */
public final class ContextRewriteScorer {

    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties props;
    private final ContractValidator validator;

    public ContextRewriteScorer(ModelGatewayClient gateway, ModelGatewayProperties props,
                                ContractValidator validator) {
        this.gateway = gateway;
        this.props = props;
        this.validator = validator;
    }

    public record Score(int passed, int total, int invalidJson, int outOfScopeRefs,
                        int unsafeDerivedAmount, int incoherentContract, List<Failure> failures) {
        @Override public String toString() {
            return String.format("通过 %d/%d 不合Schema %d 越界引用 %d 旧余额派生金额 %d 契约不一致 %d",
                    passed, total, invalidJson, outOfScopeRefs, unsafeDerivedAmount,
                    incoherentContract);
        }
    }

    public record Failure(String caseId, String query, String userPrompt,
                          String expected, String got, String note) {
    }

    public Score score(String system, List<ContextTrajectory> trajectories) {
        int passed = 0;
        int invalid = 0;
        int outOfScope = 0;
        int unsafeAmount = 0;
        int incoherent = 0;
        List<Failure> failures = new ArrayList<>();
        var config = props.getContextRewrite();

        for (ContextTrajectory trajectory : trajectories) {
            String raw = GatewayRetry.chat(gateway, new ChatRequest(
                    props.resolveLogicalModel(config), system, trajectory.userPrompt(),
                    config.getMaxTokens(), config.getTemperature(), true, null, "context-rewrite"),
                    "context 打分 " + trajectory.caseId());
            String json = stripFence(raw);
            if (!validator.validateJson(SchemaRef.CONTEXTUAL_QUERY_OUTPUT, json).valid()) {
                invalid++;
                failures.add(failure(trajectory, json, "输出不符合 context schema"));
                continue;
            }
            try {
                JsonNode node = ContractJson.mapper().readTree(json);
                List<String> diffs = new ArrayList<>();
                List<String> used = texts(node.path("usedContextRefs"));
                ContextTrajectory.Truth truth = trajectory.truth();
                boolean out = used.stream().anyMatch(ref -> !trajectory.userPrompt().contains(ref));
                boolean unsafe = node.path("slotUpdates").has("amount")
                        && !truth.slots().containsKey("amount");
                boolean coherent = coherent(node, trajectory.query(), used);
                if (out) { outOfScope++; diffs.add("引用越界"); }
                if (unsafe) { unsafeAmount++; diffs.add("输出了旧余额计算金额"); }
                if (!coherent) { incoherent++; diffs.add("resolution/引用/slot 原子契约不一致"); }

                if (truth.consumed() != !used.isEmpty()) diffs.add("上下文消费判断");
                if (truth.eventType() != null
                        && !truth.eventType().equals(node.path("eventType").asText())) {
                    diffs.add("eventType");
                }
                if (truth.resolutionType() != null && node.path("resolutions").findValuesAsText(
                        "resolutionType").stream().noneMatch(truth.resolutionType()::equals)) {
                    diffs.add("resolutionType");
                }
                if (!used.containsAll(truth.usedContextRefs())) {
                    diffs.add("usedContextRefs");
                }
                truth.slots().forEach((key, value) -> {
                    if (!value.equals(node.path("slotUpdates").path(key).asText())) {
                        diffs.add("slotUpdates." + key);
                    }
                });
                truth.forbiddenSlotKeys().forEach(key -> {
                    if (node.path("slotUpdates").has(key)) {
                        diffs.add("禁止 slotUpdates." + key);
                    }
                });
                String standalone = node.path("standaloneQuery").asText();
                truth.standaloneContains().forEach(fragment -> {
                    if (!standalone.contains(fragment)) {
                        diffs.add("standaloneQuery 缺少 " + fragment);
                    }
                });
                if (diffs.isEmpty()) {
                    passed++;
                } else {
                    failures.add(new Failure(trajectory.caseId(), trajectory.query(),
                            trajectory.userPrompt(), describe(truth), summarize(node),
                            String.join(",", diffs)));
                }
            } catch (Exception e) {
                invalid++;
                failures.add(failure(trajectory, json, "无法解析 JSON"));
            }
        }
        return new Score(passed, trajectories.size(), invalid, outOfScope,
                unsafeAmount, incoherent, List.copyOf(failures));
    }

    private static boolean coherent(JsonNode node, String original, List<String> used) {
        for (JsonNode resolution : node.path("resolutions")) {
            String mention = resolution.path("mention").asText();
            String ref = resolution.path("contextRef").asText();
            if (mention.isBlank() || !original.contains(mention) || !used.contains(ref)) return false;
            if ("REQUERY_THEN_HALF".equals(resolution.path("resolutionType").asText())
                    && !"REQUERY_THEN_HALF".equals(
                            node.path("slotUpdates").path("amountBasis").asText())) return false;
            if ("ORDINAL_REFERENCE".equals(resolution.path("resolutionType").asText())
                    && node.path("slotUpdates").path("accountOrdinal").asInt(0) < 1) return false;
        }
        boolean halfSlot = "REQUERY_THEN_HALF".equals(
                node.path("slotUpdates").path("amountBasis").asText());
        boolean halfResolution = node.path("resolutions").findValuesAsText("resolutionType")
                .contains("REQUERY_THEN_HALF");
        return halfSlot == halfResolution;
    }

    private static Failure failure(ContextTrajectory t, String got, String note) {
        return new Failure(t.caseId(), t.query(), t.userPrompt(), describe(t.truth()),
                truncate(got), note);
    }

    private static String describe(ContextTrajectory.Truth truth) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("consumed", truth.consumed());
        if (truth.eventType() != null) value.put("eventType", truth.eventType());
        if (truth.resolutionType() != null) value.put("resolutionType", truth.resolutionType());
        if (!truth.slots().isEmpty()) value.put("slots", truth.slots());
        if (!truth.usedContextRefs().isEmpty()) value.put("usedContextRefs", truth.usedContextRefs());
        if (!truth.standaloneContains().isEmpty()) {
            value.put("standaloneContains", truth.standaloneContains());
        }
        if (!truth.forbiddenSlotKeys().isEmpty()) {
            value.put("forbiddenSlotKeys", truth.forbiddenSlotKeys());
        }
        return value.toString();
    }

    private static String summarize(JsonNode node) {
        return "event=" + node.path("eventType").asText()
                + " used=" + texts(node.path("usedContextRefs"))
                + " slots=" + node.path("slotUpdates");
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String stripFence(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.startsWith("```")) return value;
        value = value.replaceFirst("^```(?:json)?\\s*", "");
        return value.replaceFirst("\\s*```$", "");
    }

    private static String truncate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }
}
