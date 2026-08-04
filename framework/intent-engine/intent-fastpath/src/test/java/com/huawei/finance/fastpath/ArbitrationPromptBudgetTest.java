package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
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
 * FP-1E 的另一半：仲裁 prompt 的候选数与体积上限。
 *
 * <p>往返数合规与单价失控可以同时成立。外部同类系统的线上日志显示，一次工具选择付到
 * 5654–7913 输入 token 换回 17–24 个输出 token（§2.7.1），原因是把 11–15 个候选连同完整
 * {@code input_schema} 全塞进了 prompt。往返数在他们那儿也只有一次。
 *
 * <p>所以这里守的不是今天的体积——今天 5 个候选拼出来约千字，离预算很远。守的是**明天**：
 * 有人往候选渲染里加一段 description 或整个 inputSchema，体积会一次涨一个数量级，
 * 而现有的任何用例、任何指标都不会因此变红。
 */
class ArbitrationPromptBudgetTest {

    private static RequestContext ctx() {
        return new RequestContext("trace-prompt", "s-prompt", "u-1", "MOBILE_BANK", "home", "", false);
    }

    /** 跑一遍快路径，返回实际发给模型的 user prompt。 */
    private static String promptFor(String query, CapturingGateway gateway) {
        FastPathFixture.build(gateway).engine()
                .decide(new FastPathRequest(ctx(), query, null, Map.of()));
        return gateway.lastUserPrompt;
    }

    @Test
    @DisplayName("prompt 里不含候选的完整 schema，只含槽位名")
    void promptCarriesSlotNamesNotSchemas() {
        CapturingGateway gateway = new CapturingGateway();
        String prompt = promptFor("我要转账给我老板两千", gateway);

        assertThat(prompt).isNotNull();
        // 槽位名要在——模型得知道缺什么才能回填
        assertThat(prompt).contains("payee").contains("amount");
        // 但 JSON Schema 的结构不得出现，那是体积失控的起点
        assertThat(prompt).doesNotContain("\"type\":").doesNotContain("properties");
    }

    @Test
    @DisplayName("候选数不超过配置上限")
    void candidateCountIsCapped() {
        CapturingGateway gateway = new CapturingGateway();
        String prompt = promptFor("我要转账给我老板两千", gateway);

        assertThat(countCandidateLines(prompt))
                .isLessThanOrEqualTo(new com.huawei.finance.gateway.ModelGatewayProperties()
                        .getArbitration().getMaxPromptCandidates());
    }

    @Test
    @DisplayName("当前 prompt 体积远在预算内，留出的余量是给资产增长的")
    void promptStaysWellWithinBudget() {
        CapturingGateway gateway = new CapturingGateway();
        String prompt = promptFor("我要转账给我老板两千", gateway);

        int budget = new com.huawei.finance.gateway.ModelGatewayProperties().getArbitration().getMaxPromptChars();
        assertThat(prompt.length()).isLessThan(budget);
    }

    /**
     * 预算调到极小值时必须真的裁剪，且**至少留一个候选**。
     *
     * <p>裁到零个候选就等于让模型做一道没有选项的选择题，它只会开始编能力 ID；
     * 那种输出会被越界校验拦下，于是白花一次往返换一次回退——比不裁还糟。
     */
    /** 真实存在的能力，用于把候选集撑到多个。 */
    private static final List<String> MANY_CANDIDATES = List.of(
            "cap.transfer", "cap.account.balance.query", "cap.account.transaction.query",
            "cap.creditcard.bill.query", "cap.wealth.holding.query");

    @Test
    @DisplayName("预算不足时逐个丢末位候选，但绝不丢到零个")
    void trimmingNeverEmptiesTheCandidateList() {
        String query = "我要转账给我老板两千";

        // 先确认不裁剪时确实不止一个候选。第一版这条断言不在，用的又是只召回单个能力的
        // 默认装配，于是「裁不到零」整条用例是空跑的——断言照过，被测分支从未执行
        CapturingGateway baseline = new CapturingGateway();
        FastPathFixture.buildWithBm25Hits(baseline, MANY_CANDIDATES).engine()
                .decide(new FastPathRequest(ctx(), query, null, Map.of()));
        assertThat(countCandidateLines(baseline.lastUserPrompt)).isGreaterThan(1);

        CapturingGateway gateway = new CapturingGateway();
        FastPathFixture.Built built = FastPathFixture.buildWithBm25Hits(gateway, MANY_CANDIDATES);
        // 把字符预算压到任何 prompt 都装不下
        built.modelProps().getArbitration().setMaxPromptChars(1);
        built.engine().decide(new FastPathRequest(ctx(), query, null, Map.of()));

        assertThat(gateway.lastUserPrompt).isNotNull();
        assertThat(countCandidateLines(gateway.lastUserPrompt)).isEqualTo(1);
    }

    @Test
    @DisplayName("候选多于上限时按上限截断")
    void excessCandidatesAreTruncated() {
        CapturingGateway gateway = new CapturingGateway();
        FastPathFixture.Built built = FastPathFixture.buildWithBm25Hits(gateway, MANY_CANDIDATES);
        built.modelProps().getArbitration().setMaxPromptCandidates(2);

        built.engine().decide(new FastPathRequest(ctx(), "我要转账给我老板两千", null, Map.of()));

        assertThat(countCandidateLines(gateway.lastUserPrompt)).isEqualTo(2);
    }

    /** 候选行形如「1. cap.xxx | 融合分 ...」，数行首编号即可。 */
    private static int countCandidateLines(String prompt) {
        if (prompt == null) {
            return 0;
        }
        int count = 0;
        for (String line : prompt.split("\n")) {
            if (line.strip().matches("^\\d+\\.\\s+cap\\..*")) {
                count++;
            }
        }
        return count;
    }

    /** 记下最后一次发出的 user prompt。 */
    private static final class CapturingGateway implements ModelGatewayClient {

        private static final String RESPONSE = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.transfer"],
                 "confidence":0.85,"reasonCode":"CONFIRMATION_REQUIRED",
                 "extractedSlots":{"payee":"我老板","amount":"2000"}}
                """;

        private String lastUserPrompt;

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            List<float[]> vectors = new ArrayList<>(inputs.size());
            inputs.forEach(i -> vectors.add(new float[1024]));
            return GatewayResult.ok(vectors, 1);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            lastUserPrompt = request.userPrompt();
            return GatewayResult.ok(RESPONSE, 1);
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
