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

/**
 * 拿一段候选 system 提示词，在冻结轨迹上打分。
 *
 * <p>打分口径是刻意「粗」的：**每条轨迹只有过与不过**，不给部分分。
 * 部分分会让优化器学会用便宜的方式换分——比如把所有拿不准的都判成 CLARIFY，
 * 于是 decision 那一项常常对，总分上去了，而系统的实际行为变成了「什么都要问一遍」。
 *
 * <p>另外三类样本单独计数，因为它们的含义完全不同，混进一个百分比就都看不见了：
 *
 * <ul>
 *   <li>{@code invalidJson}：输出不合 Schema。线上遇到这个会回退规则仲裁，
 *       所以它不是「答错」，是「白花一次往返」。</li>
 *   <li>{@code outOfScope}：选了候选之外的能力。线上有硬校验拦着，
 *       但它说明提示词对「只能从候选里选」这条约束的表达变弱了。</li>
 *   <li>{@code r2WithoutConfirmation}：模型把一个 R2（有资金动作）能力判成了无确认直出。</li>
 * </ul>
 *
 * <p><b>关于最后一项，必须说清它不是线上漏洞。</b>这里打分看的是**模型的原始输出**，
 * 而线上这份输出还要过 {@code FailSafeGuard}：风险等级是能力卡上的属性，不接受模型判断，
 * R2 一律改写成 {@code CONFIRMATION_REQUIRED}。所以这一项计数衡量的是
 * 「提示词有多依赖那道兜底」，不是「有多少笔钱会被误转」——现状提示词在这份轨迹上就有 5 条，
 * 而全量评测里 R2 无确认直出始终是 0。
 *
 * <p>那为什么还要数它：兜底是最后一道，不是第一道。模型原始输出里这类越多，
 * 说明提示词对确认这件事的表达越弱，而一旦哪天有人在别处引入一条绕过 FailSafeGuard 的路径
 * （例如新的出口、或直连模型的实验通道），这个数字就会立刻变成真实风险。
 * 它是**纵深防御的第一层的体检指标**，所以 {@link OptimizerGuard} 只要求它不许变多。
 */
public final class ArbitrationScorer {

    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties props;
    private final ContractValidator validator;
    private final java.util.Set<String> r2Capabilities;

    public ArbitrationScorer(ModelGatewayClient gateway, ModelGatewayProperties props,
                             ContractValidator validator, java.util.Set<String> r2Capabilities) {
        this.gateway = gateway;
        this.props = props;
        this.validator = validator;
        this.r2Capabilities = java.util.Set.copyOf(r2Capabilities);
    }

    /**
     * 一次评分结果。
     *
     * @param passed      判对的轨迹数
     * @param total       参与打分的轨迹数
     * @param invalidJson 输出不合 Schema 的条数
     * @param outOfScope  选了候选之外能力的条数
     * @param r2WithoutConfirmation 模型原始输出里 R2 无确认直出的条数（线上由 FailSafeGuard 兜住）
     * @param failures    未通过的轨迹详情，喂给优化器当作「编辑依据」
     */
    public record Score(int passed, int total, int invalidJson, int outOfScope,
                        int r2WithoutConfirmation, List<Failure> failures) {

        public double passRate() {
            return total == 0 ? 0 : (double) passed / total;
        }

        @Override
        public String toString() {
            return String.format(
                    "通过 %d/%d（%.0f%%）不合 Schema %d 越界选择 %d R2 漏确认（模型层，线上有兜底）%d",
                    passed, total, passRate() * 100, invalidJson, outOfScope,
                    r2WithoutConfirmation);
        }
    }

    /** 一条未通过的轨迹。给优化器看的就是这些，不给它看资产也不给它看代码。 */
    public record Failure(String caseId, String query, String userPrompt,
                          String expected, String got, String note) {
    }

    /**
     * 用候选 system 段在冻结轨迹上跑一遍。
     *
     * <p>只换 system，user 段用录下来的原文——这是「优化仲裁时召回是常量」的具体做法。
     */
    public Score score(String system, List<Trajectory> trajectories) {
        int passed = 0;
        int invalidJson = 0;
        int outOfScope = 0;
        int r2WithoutConfirmation = 0;
        List<Failure> failures = new ArrayList<>();

        for (Trajectory t : trajectories) {
            // 网关抖动不记成提示词的错，也不算它跳过：重试到成功，实在不行整轮作废。
            // 记成错会让优化器为了「修」一次超时去改提示词，而它改什么都没用；
            // 算它跳过则更糟——分母缩了，分数还照样比
            String raw = GatewayRetry.chat(gateway, new ChatRequest(
                    props.getArbitration().getModel(), system, t.userPrompt(),
                    props.getArbitration().getMaxTokens(),
                    props.getArbitration().getTemperature(), true), "打分 " + t.caseId());

            String json = stripCodeFence(raw);
            if (!validator.validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT, json).valid()) {
                invalidJson++;
                failures.add(new Failure(t.caseId(), t.query(), t.userPrompt(),
                        describe(t.truth()), truncate(json), "输出不符合结构约定"));
                continue;
            }

            Verdict verdict = judge(json, t);
            if (verdict.outOfScope()) {
                outOfScope++;
            }
            if (verdict.r2WithoutConfirmation()) {
                r2WithoutConfirmation++;
            }
            if (verdict.passed()) {
                passed++;
            } else {
                failures.add(new Failure(t.caseId(), t.query(), t.userPrompt(),
                        describe(t.truth()), verdict.got(), verdict.note()));
            }
        }

        return new Score(passed, trajectories.size(), invalidJson, outOfScope,
                r2WithoutConfirmation, failures);
    }

    private record Verdict(boolean passed, boolean outOfScope, boolean r2WithoutConfirmation,
                           String got, String note) {
    }

    private Verdict judge(String json, Trajectory t) {
        JsonNode node;
        try {
            node = ContractJson.mapper().readTree(json);
        } catch (Exception e) {
            return new Verdict(false, false, false, truncate(json), "输出不是合法 JSON");
        }

        String decision = node.path("decision").asText("");
        String reasonCode = node.path("reasonCode").asText("");
        List<String> selected = texts(node.path("candidateIds"));
        List<String> missing = texts(node.path("missingSlots"));
        String capability = selected.isEmpty() ? null : selected.get(0);

        // 越界：选了 prompt 里根本没出现过的 ID。用原文包含判断而不是解析候选块——
        // 候选块的格式属于渲染细节，工具不该因为它换了个字段顺序就失效
        boolean outOfScope = capability != null && !t.userPrompt().contains(capability);

        boolean r2WithoutConfirmation = capability != null && r2Capabilities.contains(capability)
                && "EXECUTE_CAPABILITY".equals(decision)
                && !"CONFIRMATION_REQUIRED".equals(reasonCode);

        String got = decision + " / " + reasonCode + " / " + capability
                + (missing.isEmpty() ? "" : " / missing=" + missing);

        Trajectory.Truth truth = t.truth();
        List<String> diffs = new ArrayList<>();
        if (truth.decision() != null && !truth.decision().equals(decision)) {
            diffs.add("decision");
        }
        if (truth.reasonCode() != null && !truth.reasonCode().equals(reasonCode)) {
            diffs.add("reasonCode");
        }
        if (truth.capabilityDeclared()) {
            if (truth.capability() == null && capability != null) {
                diffs.add("不该选任何能力却选了 " + capability);
            } else if (truth.capability() != null && !truth.capability().equals(capability)) {
                diffs.add("capability");
            }
        }
        if (truth.missingSlots() != null && !truth.missingSlots().equals(missing)) {
            diffs.add("missingSlots");
        }
        truth.slots().forEach((slot, want) -> {
            String actual = node.path("extractedSlots").path(slot).asText("");
            if (!want.equals(actual)) {
                diffs.add("slots[" + slot + "]=" + (actual.isEmpty() ? "空" : actual));
            }
        });

        if (outOfScope) {
            diffs.add("选了候选清单之外的能力");
        }
        if (r2WithoutConfirmation) {
            diffs.add("资金动作类能力未要求确认");
        }
        return new Verdict(diffs.isEmpty(), outOfScope, r2WithoutConfirmation, got,
                String.join(",", diffs));
    }

    private static String describe(Trajectory.Truth truth) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (truth.decision() != null) {
            fields.put("decision", truth.decision());
        }
        if (truth.reasonCode() != null) {
            fields.put("reasonCode", truth.reasonCode());
        }
        if (truth.capabilityDeclared()) {
            fields.put("capability",
                    truth.capability() == null ? "（不选任何能力）" : truth.capability());
        }
        if (truth.missingSlots() != null) {
            fields.put("missingSlots", truth.missingSlots());
        }
        if (!truth.slots().isEmpty()) {
            fields.put("slots", truth.slots());
        }
        return fields.toString();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    static String render(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    private static String stripCodeFence(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNewline = s.indexOf('\n');
        if (firstNewline > 0) {
            s = s.substring(firstNewline + 1);
        }
        int lastFence = s.lastIndexOf("```");
        return (lastFence >= 0 ? s.substring(0, lastFence) : s).trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
