package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.gateway.BudgetAwareModelGateway;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 网关往返记账（原 FP-1E 次数门的残存部分）。
 *
 * <p>次数硬上限已取消（ADR-003）。这里守的是**记账是否可信**：装饰器在、按调用次数计、
 * 失败也记、重试不重复计——看板与延迟分析靠的就是这份序列。各出口的「典型开销」仍断言，
 * 作为回归探针（强规则该是 0、完整链路该是 embedding+arbitration），但**超过 2 不再失败**。
 *
 * <p>prompt 体积上限仍在 {@code ArbitrationPromptBudgetTest}，与次数无关。
 */
class GatewayBudgetTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.clear();
    }

    /** 让 FastPathEngine 跑一遍，返回这次请求实际发生的网关调用序列。 */
    private static List<String> callsFor(String query, FastPathFixture.Built built) {
        RequestContext ctx = new RequestContext(
                "trace-budget", "s-" + query.hashCode(), "u-1", "MOBILE_BANK", "home", "", false);
        RequestContextHolder.set(ctx);
        built.engine().decide(new FastPathRequest(ctx, query, null, Map.of()));
        return ctx.gatewayCalls();
    }

    @Nested
    @DisplayName("各出口的典型往返")
    class PerExit {

        @Test
        @DisplayName("两条通道都开时典型是 embedding + 仲裁")
        void fullPathSpendsEmbeddingAndArbitration() {
            List<String> calls = callsFor("我要转账给我老板两千",
                    FastPathFixture.buildWithSemanticChannel(new FullyAvailableGateway()));

            assertThat(calls).containsExactly("embedding", "arbitration");
        }

        @Test
        @DisplayName("强规则短路一次都不调")
        void strongRuleSpendsNothing() {
            assertThat(callsFor("帮我把信用卡额度改成 10 万",
                    FastPathFixture.buildWithSemanticChannel(new FullyAvailableGateway()))).isEmpty();
        }

        @Test
        @DisplayName("语义通道降级时只剩仲裁那一次")
        void degradedSemanticSpendsOne() {
            assertThat(callsFor("我要转账给我老板两千",
                    FastPathFixture.build(new FullyAvailableGateway())))
                    .containsExactly("arbitration");
        }

        @Test
        @DisplayName("网关不可用时不产生往返，且快路径照常出结论")
        void unavailableGatewaySpendsNothing() {
            assertThat(callsFor("查一下余额",
                    FastPathFixture.build(new FastPathFixture.UnavailableGateway()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("记账本身")
    class Accounting {

        @Test
        @DisplayName("调用失败也计入往返，否则降级环境里序列等于没记")
        void failedCallsStillCount() {
            List<String> calls = callsFor("我要转账给我老板两千",
                    FastPathFixture.buildWithSemanticChannel(new AlwaysFailingButAvailableGateway()));

            assertThat(calls).isNotEmpty();
        }

        @Test
        @DisplayName("同一次调用内部重试多次，只记一次往返")
        void retriesDoNotCount() {
            AtomicInteger attempts = new AtomicInteger();
            RequestContext ctx = new RequestContext(
                    "trace-retry", "s-retry", "u-1", "MOBILE_BANK", "home", "", false);
            RequestContextHolder.set(ctx);

            ModelGatewayClient gateway = new BudgetAwareModelGateway(
                    new InternallyRetryingGateway(attempts, 3), new SimpleMeterRegistry());
            gateway.chat(new ChatRequest("m", "s", "u", 16, 0.0, true));

            assertThat(attempts).hasValue(3);
            assertThat(ctx.gatewayRoundTrips()).isEqualTo(1);
        }

        @Test
        @DisplayName("入参为空的调用不计往返")
        void emptyInputIsNotARoundTrip() {
            RequestContext ctx = new RequestContext(
                    "trace-empty", "s-empty", "u-1", "MOBILE_BANK", "home", "", false);
            RequestContextHolder.set(ctx);

            ModelGatewayClient gateway = new BudgetAwareModelGateway(
                    new FullyAvailableGateway(), new SimpleMeterRegistry());
            gateway.embed(List.of());

            assertThat(ctx.gatewayRoundTrips()).isZero();
        }

        @Test
        @DisplayName("重排调用会如实出现在序列里，不再视为超预算")
        void rerankIsJustAnotherCall() {
            RequestContext ctx = new RequestContext(
                    "trace-rerank", "s-rerank", "u-1", "MOBILE_BANK", "home", "", false);
            RequestContextHolder.set(ctx);

            ModelGatewayClient gateway = new BudgetAwareModelGateway(
                    new FullyAvailableGateway(), new SimpleMeterRegistry());
            gateway.embed(List.of("q"));
            gateway.rerank("q", List.of("a", "b"), 2);
            gateway.chat(new ChatRequest("m", "s", "u", 16, 0.0, true));

            assertThat(ctx.gatewayCalls()).containsExactly("embedding", "rerank", "arbitration");
            assertThat(ctx.gatewayRoundTrips()).isEqualTo(3);
        }
    }

    private static final class FullyAvailableGateway implements ModelGatewayClient {

        private static final String RESPONSE = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.transfer"],
                 "confidence":0.85,"reasonCode":"CONFIRMATION_REQUIRED",
                 "extractedSlots":{"payee":"我老板","amount":"2000"}}
                """;

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
            return GatewayResult.ok(List.of(), 1);
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private record InternallyRetryingGateway(AtomicInteger attempts, int retries)
            implements ModelGatewayClient {

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("stub", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            for (int i = 0; i < retries; i++) {
                attempts.incrementAndGet();
            }
            return GatewayResult.ok("{}", 1);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("stub", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final class AlwaysFailingButAvailableGateway implements ModelGatewayClient {

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("no-api-key", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("no-api-key", 0);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("no-api-key", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
