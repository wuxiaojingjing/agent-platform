package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.contracts.validation.ContractValidator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-1H：重排接线。
 *
 * <p>栽过的跟头是「配置齐全、代码跑通、开关没人读」。这条用例直接数网关调用序列与候选序，
 * 关着不调、开着改序——两者都红不了才算接线完成。
 */
class RerankWiringTest {

    @AfterEach
    void clear() {
        RequestContextHolder.clear();
    }

    @Test
    @DisplayName("默认关：完整链路只有 embedding + 仲裁，没有 rerank")
    void offByDefault() {
        CountingGateway gateway = new CountingGateway(identityRerank());
        RequestContext ctx = bind("trace-rerank-off");
        FastPathFixture.buildWithSemanticChannel(gateway)
                .engine()
                .decide(new FastPathRequest(ctx, "查一下余额", null, Map.of()));

        assertThat(ctx.gatewayCalls()).doesNotContain("rerank");
        assertThat(gateway.rerankCalls.get()).isZero();
    }

    @Test
    @DisplayName("开着重排：序列里有 rerank，且候选序跟着重排分走")
    void onReordersCandidates() {
        // 故意把融合第一名打成重排第二，第二名打成第一——接上线才能看到序被翻过来
        CountingGateway gateway = new CountingGateway(docs -> List.of(
                new RerankHit(1, 0.99),
                new RerankHit(0, 0.10)));

        AssetBundle withRerank = loadWithRerankEnabled();

        RequestContext ctx = bind("trace-rerank-on");
        FastPathResult result = FastPathFixture.build(
                withRerank, gateway,
                new FastPathFixture.StubCandidateSearch(
                        List.of("cap.account.balance.query", "cap.transfer"), true))
                .engine()
                .decide(new FastPathRequest(ctx, "查余额再转账", null, Map.of()));

        assertThat(ctx.gatewayCalls()).contains("rerank");
        assertThat(gateway.rerankCalls.get()).isEqualTo(1);

        List<RecallResult.Candidate> candidates = result.recall().candidates();
        assertThat(candidates).hasSizeGreaterThanOrEqualTo(2);
        assertThat(candidates.get(0).candidateId())
                .as("重排把下标 1 打成最高分，第一名必须换成原来的第二名")
                .isEqualTo("cap.transfer");
        assertThat(candidates.get(0).matchedEvidence())
                .anySatisfy(e -> assertThat(e).startsWith("rerank:0.990"));
    }

    @Test
    @DisplayName("开着但网关不可用：记 degraded=rerank，候选仍在")
    void unavailableDegradesWithoutEmptying() {
        CountingGateway gateway = new CountingGateway(docs -> List.of());
        gateway.failRerank = true;

        AssetBundle withRerank = loadWithRerankEnabled();

        RequestContext ctx = bind("trace-rerank-deg");
        FastPathResult result = FastPathFixture.build(
                withRerank, gateway,
                new FastPathFixture.StubCandidateSearch(
                        List.of("cap.account.balance.query"), false))
                .engine()
                .decide(new FastPathRequest(ctx, "查一下余额", null, Map.of()));

        assertThat(result.recall().degradedChannels()).contains("rerank");
        assertThat(result.recall().candidates()).isNotEmpty();
        assertThat(ctx.gatewayCalls()).contains("rerank");
    }

    private static RequestContext bind(String trace) {
        RequestContext ctx = new RequestContext(
                trace, "s-rerank", "u-1", "MOBILE_BANK", "home", "", false);
        RequestContextHolder.set(ctx);
        return ctx;
    }

    /** 每次从磁盘新装一份再开开关，避免改到别的用例手里的那份资产。 */
    private static AssetBundle loadWithRerankEnabled() {
        AssetBundle bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        bundle.fusion().getChannels().setRerankEnabled(true);
        return bundle;
    }

    private static java.util.function.Function<List<String>, List<RerankHit>> identityRerank() {
        return docs -> {
            List<RerankHit> hits = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                hits.add(new RerankHit(i, 1.0 - i * 0.01));
            }
            return hits;
        };
    }

    private static final class CountingGateway implements ModelGatewayClient {

        private static final String RESPONSE = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.account.balance.query"],
                 "confidence":0.9,"reasonCode":"HIGH_CONFIDENCE","extractedSlots":{}}
                """;

        private final java.util.function.Function<List<String>, List<RerankHit>> reranker;
        private final AtomicInteger rerankCalls = new AtomicInteger();
        private boolean failRerank;

        private CountingGateway(java.util.function.Function<List<String>, List<RerankHit>> reranker) {
            this.reranker = reranker;
        }

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            List<float[]> vectors = new ArrayList<>(inputs.size());
            inputs.forEach(i -> vectors.add(new float[1024]));
            return GatewayResult.ok(vectors, 1);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok(RESPONSE, 1);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            rerankCalls.incrementAndGet();
            if (failRerank) {
                return GatewayResult.unavailable("rerank-disabled", 0);
            }
            return GatewayResult.ok(reranker.apply(documents), 1);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
