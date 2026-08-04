package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.obs.trace.ScoredCandidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;

/**
 * FP-62 在快路径上的接线。
 *
 * <p>与 {@code MicrometerDecisionTraceTest} 分工不同：那边验「记录器把内容写成什么样的 span」，
 * 这边验「引擎到底把什么交了出去」。两件事都可能单独错——记录器完全正确而引擎压根没调，
 * 或者引擎交了却把顺序弄反——只测一头都发现不了。
 */
class DecisionTraceWiringTest {

    @Test
    @DisplayName("快路径自动配置必须晚于观测配置，不能在 DecisionTrace 建立前固化 NOOP")
    void autoConfigurationWaitsForDecisionTrace() {
        AutoConfigureAfter ordering = FastPathConfiguration.class.getAnnotation(AutoConfigureAfter.class);

        assertThat(ordering.name()).contains("com.huawei.finance.obs.ObsAutoConfiguration");
    }

    private static RequestContext ctx(String sessionId) {
        return new RequestContext("trace-" + sessionId, sessionId, "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", false);
    }

    private static FastPathFixture.Built fixture() {
        // 每个用例各建一份：RecordingDecisionTrace 有状态，共用静态夹具会让
        // 用例之间靠执行顺序互相干扰
        return FastPathFixture.build();
    }

    private static void decide(FastPathFixture.Built built, String sessionId, String query) {
        built.engine().decide(new FastPathRequest(ctx(sessionId), query, null, Map.of()));
    }

    @Test
    @DisplayName("走到仲裁时交出候选集，并按融合分降序")
    void handsOverRankedCandidates() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-balance", "查一下余额");

        List<ScoredCandidate> candidates = built.trace().lastCandidates();
        assertThat(candidates).isNotEmpty();
        assertThat(candidates).extracting(ScoredCandidate::candidateId).doesNotContainNull();
        assertThat(candidates).extracting(ScoredCandidate::fusedScore)
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    @DisplayName("候选集里不含用户原话或槽位——ScoredCandidate 在类型上就装不下")
    void candidatesCarryNoUserText() {
        FastPathFixture.Built built = fixture();
        // 这句里「老徐」是像人名的实体、「1000」会被抽成金额槽位，两者都不该出现在观测里
        decide(built, "s-transfer", "给老徐转 1000");

        for (ScoredCandidate c : built.trace().lastCandidates()) {
            assertThat(c.candidateId()).doesNotContain("老徐").doesNotContain("1000");
            assertThat(c.evidence()).noneMatch(e -> e.contains("老徐"));
        }
    }

    @Test
    @DisplayName("三个阶段的耗时都交了，且都是正数")
    void handsOverAllPhases() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-phase", "查一下余额");

        assertThat(built.trace().phaseNanos)
                .containsKeys(FastPathEngine.PHASE_REWRITE, FastPathEngine.PHASE_RECALL,
                        FastPathEngine.PHASE_ARBITRATION);
        assertThat(built.trace().phaseNanos.values()).allMatch(nanos -> nanos > 0);
    }

    @Test
    @DisplayName("模型不可用而走规则回退时，「由谁定」必须是 RULE_FALLBACK")
    void marksRuleFallback() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-fallback", "查一下余额");

        // 出口形态与模型判出来的一模一样，只有这个字段能区分。分不出来的话，
        // 「今天 CLARIFY 涨了」就无法定位成「模型挂了」还是「模型真这么判的」
        assertThat(built.trace().arbitratedBy).isEqualTo("RULE_FALLBACK");
        assertThat(built.trace().shortCircuit).isEqualTo(ShortCircuitLevel.NONE.name());
    }

    @Test
    @DisplayName("强规则短路时标 SHORT_CIRCUIT，且不交候选集——压根没算召回")
    void strongRuleShortCircuitHasNoCandidates() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-limit", "帮我把信用卡额度改成 10 万");

        assertThat(built.trace().arbitratedBy).isEqualTo("SHORT_CIRCUIT");
        // 「没有候选集」与「候选集为空」是两件事：前者是短路，后者是召回全军覆没。
        // 短路时压根不该调 recordCandidates
        assertThat(built.trace().candidateRounds).isEmpty();
        // 但阶段耗时仍要有 rewrite——短路也走了改写
        assertThat(built.trace().phaseNanos).containsKey(FastPathEngine.PHASE_REWRITE);
    }

    /**
     * FP-62 的对照那一半：光有记录器不算做完，得有人调它。
     *
 * <p>这条同时是 §7 变更规则第 5 条要求的「消费点存在」证据。已经栽过三次同一个跟头
 * （keywords 通道、能力卡 timeoutMs、以及接线前的 rerankEnabled），共同点都是配置齐全、
 * 代码跑通、功能实际不工作，且不会让任何用例变红。
     */
    @Test
    @DisplayName("走到仲裁时交出前后对照，且交的是与候选集同一份排名")
    void handsOverArbitrationComparison() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-compare", "查一下余额");

        assertThat(built.trace().comparisonRounds).isEqualTo(1);
        // 同一份排名，不是各自算的两份——否则「模型是否推翻了检索第一名」这个结论
        // 就取决于两次排序算法没有分歧
        assertThat(built.trace().comparisonBefore).isEqualTo(built.trace().lastCandidates());
        assertThat(built.trace().comparisonSelected).isEqualTo(built.trace().selectedId);
    }

    @Test
    @DisplayName("强规则短路时压根不交对照：没算召回，无从对照")
    void shortCircuitHandsOverNoComparison() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-limit-compare", "帮我把信用卡额度改成 10 万");

        assertThat(built.trace().comparisonRounds).isZero();
    }

    @Test
    @DisplayName("出口与原因照实交出，不做二次加工")
    void handsOverDecisionAsIs() {
        FastPathFixture.Built built = fixture();
        decide(built, "s-card", "换卡");

        assertThat(built.trace().decision).isEqualTo("CLARIFY");
        assertThat(built.trace().reasonCode).isEqualTo("MISSING_SLOT");
        assertThat(built.trace().selectedId).isEqualTo("cap.card.replace");
    }
}
