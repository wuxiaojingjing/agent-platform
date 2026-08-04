package com.huawei.finance.fastpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.fastpath.eval.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一条评测用例跑成一份可比对的结果（FP-52）。
 *
 * <p>不引入独立的执行路径：调的是生产用的 {@link FastPathEngine#decide}，装配也是
 * {@link FastPathFixture}。评测若走自己那条捷径，评的就是捷径而不是线上那条链路——
 * 这类「评测环境专用分支」是效果数字与线上表现脱节最常见的来源。
 */
final class FastPathEvalRunner {

    private FastPathEvalRunner() {
    }

    /**
     * 实测结果的扁平投影。只留评测口径关心的字段。
     *
     * @param routingMode 领域路由模式；短路路径下没有召回结果，此时为 null
     */
    record Actual(String decision, String reasonCode, String capability, List<String> missingSlots,
                  String shortCircuit, String templateKey, String routingMode,
                  Map<String, Object> slots) {

        static Actual of(FastPathResult result) {
            RouteDecision d = result.decision();
            String routing = result.recall() == null || result.recall().domainRouting() == null
                    ? null : String.valueOf(result.recall().domainRouting().routingMode());
            return new Actual(
                    String.valueOf(d.decision()),
                    String.valueOf(d.reasonCode()),
                    d.selectedCandidateId(),
                    d.missingSlots(),
                    String.valueOf(d.shortCircuit()),
                    result.templateKey(),
                    routing,
                    result.slots());
        }
    }

    /**
     * 每条用例都用一套新装配跑。
     *
     * <p>共用装配会让一级出口缓存把前一条用例的结论带给后一条：症状是单跑绿、全跑红，
     * 或者更糟——顺序一换结果就变，而没人会怀疑是缓存。
     */
    static Actual run(EvalCase testCase) {
        FastPathFixture.Built built = FastPathFixture.build();
        return run(built, testCase);
    }

    static Actual run(FastPathFixture.Built built, EvalCase testCase) {
        return observe(built, testCase).actual();
    }

    /**
     * 结果 + 送进仲裁的候选集。
     *
     * <p>候选集是归因的判据：真值能力不在里面就是召回的问题，在里面却没被选中才是仲裁的问题
     * （见 {@link com.huawei.finance.fastpath.eval.Culprit}）。只看最终出口无法区分这两件事，
     * 而它们的修法互相替代不了。
     */
    record Observation(Actual actual, FastPathResult result, List<String> candidateIds) {
    }

    static Observation observe(FastPathFixture.Built built, EvalCase testCase) {
        RequestContext ctx = new RequestContext(
                "trace-eval", "s-eval-" + testCase.getId(), "u-eval", "space-eval",
                "MOBILE_BANK", testCase.getPage(), testCase.getUserState(), false);
        FastPathResult result = built.engine().decide(
                new FastPathRequest(ctx, testCase.getQuery(), null, Map.of()));

        List<String> candidateIds = built.trace().lastCandidates().stream()
                .map(com.huawei.finance.obs.trace.ScoredCandidate::candidateId)
                .toList();
        return new Observation(Actual.of(result), result, candidateIds);
    }

    /** 比对现状锁与实测。 */
    static List<String> diff(EvalCase testCase, Actual actual) {
        return diff(testCase.getExpect(), actual);
    }

    /** 比对任意一栏期望与实测，返回人能直接读懂的差异描述；一致时返回空列表。 */
    static List<String> diff(EvalCase.Expect expect, Actual actual) {
        return diff(expect, actual, true);
    }

    /**
     * 只比对**与配置无关**的字段，用于对照 truth 栏。
     *
     * <p>排除 {@code shortCircuit} 与 {@code templateKey}：前者记的是「哪一层做了决定」，
     * 规则仲裁是 NONE、模型仲裁是 L3_MODEL，同一个正确答案在两档下必然不同；
     * 后者只在强规则命中时才有值。把这两个算进真值，会让「接上模型」这件事本身
     * 被判成一堆判错——第一版诊断就是这么把 balance.plain 归到「仲裁问题」里的。
     */
    static List<String> diffTruth(EvalCase.Expect truth, Actual actual) {
        return diff(truth, actual, false);
    }

    private static List<String> diff(EvalCase.Expect expect, Actual actual, boolean includeMechanics) {
        List<String> diffs = new ArrayList<>();

        compare(diffs, "decision", expect.getDecision(), actual.decision());
        compare(diffs, "reasonCode", expect.getReasonCode(), actual.reasonCode());
        compare(diffs, "capability", expect.getCapability(), actual.capability());
        if (includeMechanics) {
            compare(diffs, "shortCircuit", expect.getShortCircuit(), actual.shortCircuit());
            compare(diffs, "templateKey", expect.getTemplateKey(), actual.templateKey());
        }
        compare(diffs, "routingMode", expect.getRoutingMode(), actual.routingMode());

        // 空列表与不写是两回事：写了空列表就是在断言「一个槽都不该缺」
        if (expect.getMissingSlots() != null
                && !expect.getMissingSlots().equals(actual.missingSlots())) {
            diffs.add("missingSlots 期望 " + expect.getMissingSlots() + " 实测 " + actual.missingSlots());
        }

        // 槽位按子集比：期望里写了哪个就校哪个。要求全等会让用例被无关槽位的新增打红
        expect.getSlots().forEach((name, want) -> {
            Object got = actual.slots().get(name);
            if (got == null || !want.equals(String.valueOf(got))) {
                diffs.add("slots[" + name + "] 期望 " + want + " 实测 " + got);
            }
        });

        return diffs;
    }

    private static void compare(List<String> diffs, String field, String expected, String actual) {
        if (expected != null && !expected.equals(actual)) {
            diffs.add(field + " 期望 " + expected + " 实测 " + actual);
        }
    }

    /** 把实测结果打成一行 YAML 片段，便于扩充用例时照抄进种子集。 */
    static String asYamlExpect(Actual actual) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("decision", actual.decision());
        fields.put("reasonCode", actual.reasonCode());
        if (actual.capability() != null) {
            fields.put("capability", actual.capability());
        }
        fields.put("shortCircuit", actual.shortCircuit());
        if (actual.templateKey() != null) {
            fields.put("templateKey", actual.templateKey());
        }
        if (!actual.missingSlots().isEmpty()) {
            fields.put("missingSlots", actual.missingSlots().toString());
        }
        return fields.toString();
    }
}
