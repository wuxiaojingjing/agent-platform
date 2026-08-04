package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.event.ActiveTaskView;
import com.huawei.finance.contracts.model.RouteDecision;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 图执行与顺序执行必须逐位一致。
 *
 * <p>为什么现有的 162 条不够：它们证明的是「跑到的分支结果对」。把编排换成图之后，
 * 真正的风险在**没跑到的组合**上——某个条件边接错，只在「有活跃任务且澄清重试且强规则命中」
 * 这类叠加场景里才现形，而那种场景没有单独的用例。这里用同一份语料把两条路径各跑一遍，
 * 比对出口的每一个字段。
 *
 * <p>两条路径各用**独立装配**：共用一个的话，先跑的那条会把结果写进缓存，
 * 后跑的那条命中 L1，比出来的「一致」是假的。
 */
class FastPathParityTest {

    /**
     * 语料。刻意覆盖四个出口、三级短路与几种上下文叠加。
     *
     * @param name       用例名，失败时要一眼看出是哪种组合
     * @param query      用户原话
     * @param activeTask 活跃任务，null 表示新会话
     * @param clarifyRetry 是否澄清补充后的重试（为真时必须绕过一级缓存）
     * @param warmCache  是否先跑一遍把缓存焐热，用来覆盖 L1 命中路径
     */
    private record Case(String name, String query, ActiveTaskView activeTask,
                        boolean clarifyRetry, boolean warmCache) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static ActiveTaskView clarifyPending() {
        return new ActiveTaskView("t-1", "CLARIFY_PENDING", "card", "cap.card.replace",
                "cardType", List.of("信用卡", "借记卡"), Map.of(), 1);
    }

    private static ActiveTaskView confirmPending() {
        return new ActiveTaskView("t-2", "CONFIRM_PENDING", "payment", "cap.transfer",
                null, List.of(), Map.of("payee", "老徐", "amount", "1000"), 0);
    }

    private static ActiveTaskView clarifyExhausted() {
        return new ActiveTaskView("t-3", "CLARIFY_PENDING", "card", "cap.card.replace",
                "cardType", List.of("信用卡", "借记卡"), Map.of(), 99);
    }

    static Stream<Case> corpus() {
        return Stream.of(
                new Case("新会话-命中能力", "查一下余额", null, false, false),
                new Case("新会话-缺槽位", "换卡", null, false, false),
                new Case("新会话-多任务", "查余额，再给老徐转 1000；不足就别转", null, false, false),
                new Case("新会话-强规则拒绝", "帮我把信用卡额度改成 10 万", null, false, false),
                new Case("新会话-无关问题", "今天天气怎么样", null, false, false),
                new Case("新会话-空白输入", "  ", null, false, false),
                new Case("缓存命中", "查一下余额", null, false, true),
                new Case("澄清重试绕过缓存", "换卡", null, true, true),
                new Case("续轮-补充槽位", "信用卡", clarifyPending(), false, false),
                new Case("续轮-补充后仍缺", "嗯", clarifyPending(), false, false),
                new Case("续轮-澄清轮数耗尽", "信用卡", clarifyExhausted(), false, false),
                new Case("续轮-确认", "确认", confirmPending(), false, false),
                new Case("续轮-取消", "算了不转了", confirmPending(), false, false),
                new Case("续轮-话题切换", "查一下余额", clarifyPending(), false, false),
                new Case("续轮叠加澄清重试", "信用卡", clarifyPending(), true, false),
                new Case("有活跃任务且命中强规则", "帮我把信用卡额度改成 10 万", clarifyPending(), false, false));
    }

    private static RequestContext ctx(Case testCase) {
        return new RequestContext("trace-parity", "s-parity", "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", testCase.clarifyRetry());
    }

    private static FastPathRequest request(Case testCase) {
        return new FastPathRequest(ctx(testCase), testCase.query(), testCase.activeTask(), Map.of());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("图执行与顺序执行的出口逐字段全等")
    void graphMatchesSequential(Case testCase) {
        FastPathFixture.Built graphFixture = FastPathFixture.build();
        FastPathFixture.Built sequentialFixture = FastPathFixture.build();

        if (testCase.warmCache()) {
            // 先用一次非澄清重试的请求把缓存焐热，两侧各焐各的
            Case warm = new Case(testCase.name(), testCase.query(), testCase.activeTask(), false, false);
            graphFixture.engine().decide(request(warm));
            sequentialFixture.engine().decideSequentially(request(warm));
        }

        FastPathResult graph = graphFixture.engine().decide(request(testCase));
        FastPathResult sequential = sequentialFixture.engine().decideSequentially(request(testCase));

        assertDecisionsEqual(graph.decision(), sequential.decision());
        assertThat(graph.slots()).isEqualTo(sequential.slots());
        assertThat(graph.templateKey()).isEqualTo(sequential.templateKey());
        assertThat(graph.rewrite()).isEqualTo(sequential.rewrite());
        assertThat(graph.event()).isEqualTo(sequential.event());
        assertThat(graph.recall() == null).isEqualTo(sequential.recall() == null);
        if (graph.recall() != null) {
            assertThat(graph.recall().candidates()).isEqualTo(sequential.recall().candidates());
            assertThat(graph.recall().degradedChannels()).isEqualTo(sequential.recall().degradedChannels());
        }
    }

    /**
     * 逐字段比而不是 {@code isEqualTo}。
     *
     * <p>{@code RouteDecision} 若某天变成 record，全等比较会顺带把新增字段也比上，
     * 那是好事；但它现在不是，直接比会退化成引用相等，用例变成永远绿的空壳。
     */
    private static void assertDecisionsEqual(RouteDecision graph, RouteDecision sequential) {
        assertThat(graph.decision()).isEqualTo(sequential.decision());
        assertThat(graph.reasonCode()).isEqualTo(sequential.reasonCode());
        assertThat(graph.shortCircuit()).isEqualTo(sequential.shortCircuit());
        assertThat(graph.candidateIds()).isEqualTo(sequential.candidateIds());
        assertThat(graph.missingSlots()).isEqualTo(sequential.missingSlots());
        assertThat(graph.evidenceRefs()).isEqualTo(sequential.evidenceRefs());
        assertThat(graph.confidence()).isEqualTo(sequential.confidence());
        assertThat(graph.configVersion()).isEqualTo(sequential.configVersion());
        assertThat(graph.modelVersion()).isEqualTo(sequential.modelVersion());
        assertThat(graph.promptVersion()).isEqualTo(sequential.promptVersion());
    }

    @Test
    @DisplayName("比对本身不是空跑：语料确实走过了三级短路的每一级")
    void corpusActuallyCoversEveryShortCircuitLevel() {
        List<String> levels = corpus()
                .map(testCase -> {
                    FastPathFixture.Built fixture = FastPathFixture.build();
                    if (testCase.warmCache()) {
                        Case warm = new Case(testCase.name(), testCase.query(),
                                testCase.activeTask(), false, false);
                        fixture.engine().decide(request(warm));
                    }
                    return String.valueOf(fixture.engine().decide(request(testCase))
                            .decision().shortCircuit());
                })
                .distinct()
                .toList();

        assertThat(levels)
                .as("少了哪一级，那一级的条件边就没有被比对过——而条件边正是这次改动的全部内容")
                .contains("CONTINUATION", "L1_CACHE", "L2_STRONG_RULE", "NONE");
    }

    @Test
    @DisplayName("图执行也把网关往返记进了本次请求")
    void graphKeepsGatewayAccounting() {
        FastPathFixture.Built fixture = FastPathFixture.buildWithSemanticChannel(
                new FastPathFixture.UnavailableGateway());
        RequestContext ctx = new RequestContext("trace-acct", "s-acct", "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", false);

        fixture.engine().decide(new FastPathRequest(ctx, "查一下余额", null, Map.of()));

        assertThat(ctx.gatewayCalls())
                .as("图引擎不在调用者线程上跑节点，RequestContext 是 ThreadLocal；"
                        + "不搬运的话往返序列会静默丢光——计数恒为 0")
                .isNotEmpty();
    }
}
