package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.fastpath.cache.DecisionCacheKey;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 一级出口缓存的键构成与绕行规则（v0.7 §3.3、§2.1.1 注 4）。 */
class DecisionCacheTest {

    private static final List<String> DIMENSIONS = List.of("loginStatus", "customerTier");

    private static RequestContext ctx(String channel, String page, boolean clarifyRetry) {
        return new RequestContext("trace-1", "s-cache", "u-1", channel, page, "", clarifyRetry);
    }

    private static String key(RequestContext ctx, String assetVersion, Map<String, String> state) {
        return DecisionCacheKey.of(ctx, "查一下余额", assetVersion, "Qwen/Qwen3-Embedding-0.6B",
                "emb-instruct-v2", "arb-skill-v1", DIMENSIONS, state);
    }

    @Test
    @DisplayName("资产版本变了，缓存键必须变")
    void keyIncludesAssetVersion() {
        RequestContext ctx = ctx("MOBILE_BANK", "home", false);

        String before = key(ctx, "assets-v1.0.0+aaaaaaaa", Map.of());
        String after = key(ctx, "assets-v1.0.0+bbbbbbbb", Map.of());

        // 只改内容忘了升版本号也要变：摘要参与了版本串
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("渠道、页面、用户状态各自独立影响缓存键")
    void keyIncludesContextDimensions() {
        String base = key(ctx("MOBILE_BANK", "home", false), "v1", Map.of("loginStatus", "LOGGED_IN"));

        assertThat(base).isNotEqualTo(key(ctx("WECHAT", "home", false), "v1",
                Map.of("loginStatus", "LOGGED_IN")));
        assertThat(base).isNotEqualTo(key(ctx("MOBILE_BANK", "transfer", false), "v1",
                Map.of("loginStatus", "LOGGED_IN")));
        assertThat(base).isNotEqualTo(key(ctx("MOBILE_BANK", "home", false), "v1",
                Map.of("loginStatus", "GUEST")));
    }

    @Test
    @DisplayName("白名单之外的用户状态不进键，否则命中率会归零")
    void keyIgnoresDimensionsOutsideWhitelist() {
        RequestContext ctx = ctx("MOBILE_BANK", "home", false);

        String withExtra = key(ctx, "v1", Map.of("loginStatus", "LOGGED_IN", "lastClickTime", "12:01"));
        String without = key(ctx, "v1", Map.of("loginStatus", "LOGGED_IN"));

        assertThat(withExtra).isEqualTo(without);
    }

    @Test
    @DisplayName("同一请求第二次命中一级缓存")
    void secondIdenticalRequestHitsCache() {
        FastPathFixture.Built fixture = FastPathFixture.build();
        FastPathRequest request = new FastPathRequest(
                ctx("MOBILE_BANK", "home", false), "查一下余额", null, Map.of());

        FastPathResult first = fixture.engine().decide(request);
        FastPathResult second = fixture.engine().decide(request);

        assertThat(first.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.NONE);
        assertThat(second.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L1_CACHE);
        // 命中缓存不改写原因码：出口为什么是这个，和这次算没算，是两个维度
        assertThat(second.decision().reasonCode()).isEqualTo(first.decision().reasonCode());
        assertThat(second.decision().candidateIds()).isEqualTo(first.decision().candidateIds());
        assertThat(fixture.cache().writes).isEqualTo(1);
    }

    @Test
    @DisplayName("澄清重试绕过一级缓存，既不读也不写")
    void clarifyRetryBypassesCache() {
        FastPathFixture.Built fixture = FastPathFixture.build();

        fixture.engine().decide(new FastPathRequest(
                ctx("MOBILE_BANK", "home", false), "查一下余额", null, Map.of()));
        int readsAfterFirst = fixture.cache().reads;
        int writesAfterFirst = fixture.cache().writes;

        fixture.engine().decide(new FastPathRequest(
                ctx("MOBILE_BANK", "home", true), "查一下余额", null, Map.of()));

        // 上一轮正因信息不全才被缓存成 CLARIFY，补充后再读缓存只会把同一个问题再问一遍
        assertThat(fixture.cache().reads).isEqualTo(readsAfterFirst);
        assertThat(fixture.cache().writes).isEqualTo(writesAfterFirst);
    }

    @Test
    @DisplayName("平台焦点、PendingGoal 或恢复上下文存在时绕过普通入口缓存")
    void platformContinuationContextsBypassCache() {
        for (String marker : List.of("continuationContext", "pendingSwitch", "resumeContext")) {
            FastPathFixture.Built fixture = FastPathFixture.build();
            FastPathRequest ordinary = new FastPathRequest(
                    ctx("MOBILE_BANK", "home", false), "查一下余额", null, Map.of());
            fixture.engine().decide(ordinary);
            int reads = fixture.cache().reads;
            int writes = fixture.cache().writes;

            fixture.engine().decide(new FastPathRequest(
                    ctx("MOBILE_BANK", "home", false), "查一下余额", null,
                    Map.of(marker, "true")));

            assertThat(fixture.cache().reads).as(marker + " reads").isEqualTo(reads);
            assertThat(fixture.cache().writes).as(marker + " writes").isEqualTo(writes);
        }
    }

    @Test
    @DisplayName("存在上下文证据时绕过普通入口缓存，避免把历史相关结论当成无上下文结论")
    void intentContextEvidenceBypassesOrdinaryDecisionCache() {
        FastPathFixture.Built fixture = FastPathFixture.build();
        FastPathRequest ordinary = new FastPathRequest(
                ctx("MOBILE_BANK", "home", false), "查一下余额", null, Map.of());
        fixture.engine().decide(ordinary);
        int reads = fixture.cache().reads;
        int writes = fixture.cache().writes;

        ContextEvidence history = new ContextEvidence("turn:s-cache#1:utterance",
                ContextEvidence.Kind.USER_TURN, Map.of("text", "上一轮原话"),
                "agent.entry", null, "turn:s-cache#1", Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        IntentContext context = new IntentContext("lease", "s-cache", "查一下余额", 2, true,
                Instant.now().plusSeconds(30), Map.of(), List.of(history), 0);
        fixture.engine().decide(new FastPathRequest(
                ctx("MOBILE_BANK", "home", false), "查一下余额", null, Map.of(),
                ContextualQuery.identity("查一下余额", 2, context.evidenceRefs()), context));

        assertThat(fixture.cache().reads).isEqualTo(reads);
        assertThat(fixture.cache().writes).isEqualTo(writes);
    }

    @Test
    @DisplayName("强规则直出不写缓存：规则求值比一次 Redis 往返还快")
    void strongRuleExitDoesNotTouchCache() {
        FastPathFixture.Built fixture = FastPathFixture.build();

        FastPathResult result = fixture.engine().decide(new FastPathRequest(
                ctx("MOBILE_BANK", "home", false), "帮我把信用卡额度改成 10 万", null, Map.of()));

        assertThat(result.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L2_STRONG_RULE);
        assertThat(fixture.cache().writes).isZero();
    }

    @Test
    @DisplayName("模型失败产生的 LOW_MARGIN 不进入缓存，模型恢复后可以重新判定")
    void transientLowMarginFallbackIsNotCacheable() {
        RouteDecision fallback = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.LOW_MARGIN)
                .confidence(0.49)
                .promptVersion("route-shape-v1")
                .build();
        RouteDecision modeled = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.LOW_MARGIN)
                .confidence(0.49)
                .modelVersion("model-v1")
                .promptVersion("route-shape-v1")
                .build();

        assertThat(FastPathSteps.cacheableDecision(fallback)).isFalse();
        assertThat(FastPathSteps.cacheableDecision(modeled)).isTrue();
    }
}
