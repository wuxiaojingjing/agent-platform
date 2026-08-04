package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-19：一级出口缓存的**代价**判定。
 *
 * <p>{@link DecisionCacheTest} 验的是缓存机制对不对（键怎么构成、什么时候绕行）。这里验的是
 * 它到底省下了什么——两者不能互相替代：缓存标志位打对了但模型照调不误，机制用例全绿，
 * 账单照涨。
 *
 * <p>反例来自外部同类系统（§2.7.1）：同一句话连发多次，每次都完整走一遍模型选择，
 * 每次都付 5654–7913 输入 token。用户在页面上连点五下「查余额」不是异常行为，是常态。
 */
class RepeatedInputCostTest {

    private static RequestContext ctx(String channel) {
        return new RequestContext("trace-cost", "s-cost", "u-1", channel, "home", "", false);
    }

    @Test
    @DisplayName("同一句话连发 5 次，只付一次模型代价")
    void fiveIdenticalRequestsPayOnce() {
        CountingGateway gateway = new CountingGateway();
        FastPathFixture.Built fixture = FastPathFixture.buildWithSemanticChannel(gateway);
        FastPathRequest request = new FastPathRequest(ctx("MOBILE_BANK"), "查一下余额", null, Map.of());

        List<FastPathResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(fixture.engine().decide(request));
        }

        // 第一次两条通道都要花钱：embedding 一次、仲裁一次，正好是 A 线预算上限
        assertThat(gateway.embedCalls).isEqualTo(1);
        assertThat(gateway.chatCalls).isEqualTo(1);

        // 第一次是真算的（走到三级模型仲裁），后四次全部由一级缓存直出
        assertThat(results.get(0).decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L3_MODEL);
        assertThat(results.subList(1, 5))
                .allMatch(r -> r.decision().shortCircuit() == ShortCircuitLevel.L1_CACHE);
    }

    /**
     * 缓存键取的是改写后的文本，几种口语说法归一到同一个键。
     *
     * <p>这正是缓存键不用原话的理由：真实流量里同一个意思有十几种说法，按原话做键，
     * 命中率会低到让这层缓存基本不起作用。
     */
    @Test
    @DisplayName("口语变体归一后共用一个缓存条目")
    void colloquialVariantsShareOneEntry() {
        CountingGateway gateway = new CountingGateway();
        FastPathFixture.Built fixture = FastPathFixture.buildWithSemanticChannel(gateway);

        // 两句话都被同义表归一成「余额」
        fixture.engine().decide(new FastPathRequest(ctx("MOBILE_BANK"), "卡里还有多少钱", null, Map.of()));
        FastPathResult second = fixture.engine()
                .decide(new FastPathRequest(ctx("MOBILE_BANK"), "卡里有多少钱", null, Map.of()));

        assertThat(second.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L1_CACHE);
        assertThat(gateway.chatCalls).isEqualTo(1);
    }

    /**
     * 反向对照。没有这条，上面两条用例在「缓存永远命中」的错误实现下也会全绿——
     * 那种实现会让换了渠道的用户拿到别人渠道的判定。
     */
    @Test
    @DisplayName("换个渠道就该重新算，缓存不是无条件命中")
    void differentChannelPaysAgain() {
        CountingGateway gateway = new CountingGateway();
        FastPathFixture.Built fixture = FastPathFixture.buildWithSemanticChannel(gateway);

        fixture.engine().decide(new FastPathRequest(ctx("MOBILE_BANK"), "查一下余额", null, Map.of()));
        FastPathResult other = fixture.engine()
                .decide(new FastPathRequest(ctx("WECHAT"), "查一下余额", null, Map.of()));

        assertThat(other.decision().shortCircuit()).isNotEqualTo(ShortCircuitLevel.L1_CACHE);
        assertThat(gateway.chatCalls).isEqualTo(2);
    }

    /**
     * 缓存里存的是出口，不是拆解——{@code RouteDecision} 契约里没有这个字段。
     *
     * <p>命中时若照直返回，多意图会退化成一句列不出选项的「先办哪一件」，跨轮续办跟着失效，
     * 而 TTL 是十分钟：同一句话在这十分钟里次次如此。出口一模一样，回复却不一样，
     * 这种缺陷只看出口的评测永远发现不了。
     */
    @Test
    @DisplayName("多意图命中缓存，拆解也得跟着出来")
    void cachedMultiIntentStillCarriesItsPlan() {
        CountingGateway gateway = new CountingGateway(MULTI_INTENT_RESPONSE);
        FastPathFixture.Built fixture = FastPathFixture.buildWithSemanticChannel(gateway);
        FastPathRequest request =
                new FastPathRequest(ctx("MOBILE_BANK"), "先查余额，不足就别转", null, Map.of());

        FastPathResult first = fixture.engine().decide(request);
        FastPathResult cached = fixture.engine().decide(request);

        assertThat(cached.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L1_CACHE);
        assertThat(gateway.chatCalls)
                .as("首轮允许 TaskShapeModel 精化计划，缓存命中不应重复调用模型")
                .isEqualTo(1);
        assertThat(cached.intentPlan())
                .as("命中缓存的那一轮把拆解丢了，用户就只剩一句问不出结果的澄清")
                .isNotNull();
        assertThat(cached.intentPlan().summaries())
                .isEqualTo(first.intentPlan().summaries());
    }

    private static final String MULTI_INTENT_RESPONSE = """
            {"decision":"STATIC_PLAN","taskShape":"CONDITIONAL_PLAN","candidateIds":[],
             "confidence":0.9,"reasonCode":"MULTI_INTENT","extractedSlots":{}}
            """;

    /** 数调用次数的网关。返回内容固定，本用例只关心花了几次钱。 */
    private static final class CountingGateway implements ModelGatewayClient {

        private static final String BALANCE = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.account.balance.query"],
                 "confidence":0.93,"reasonCode":"HIGH_CONFIDENCE","extractedSlots":{}}
                """;

        private final String response;
        private int embedCalls;
        private int chatCalls;

        private CountingGateway() {
            this(BALANCE);
        }

        private CountingGateway(String response) {
            this.response = response;
        }

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            embedCalls++;
            List<float[]> vectors = new ArrayList<>(inputs.size());
            inputs.forEach(i -> vectors.add(new float[1024]));
            return GatewayResult.ok(vectors, 1);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            chatCalls++;
            return GatewayResult.ok(response, 1);
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
}
