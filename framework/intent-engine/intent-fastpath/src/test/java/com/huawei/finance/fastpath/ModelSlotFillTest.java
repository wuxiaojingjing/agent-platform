package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 仲裁模型回填槽位。
 *
 * <p>这次模型调用原本只做选择题：读完整句话，只回答「是哪个能力」，句子里的收款人和金额
 * 随手扔掉，留给正则重抽一遍——而正则抽不到「两千」，也抽不到「我老板」。
 * 既然往返已经花了，填空题就该一并做掉。
 *
 * <p>回填不是无条件采信。下面四条分别验证：能补上正则抽不到的、不覆盖正则抽到的、
 * 不接受能力卡没声明的、补齐之后不再多问一句。
 */
class ModelSlotFillTest {

    private static RequestContext ctx(String session) {
        return new RequestContext("trace-" + session, session, "u-1", "MOBILE_BANK", "home", "", false);
    }

    private static FastPathResult decide(String json, String query) {
        return FastPathFixture.build(new SlotFillingGateway(json)).engine()
                .decide(new FastPathRequest(ctx("s-" + query.hashCode()), query, null, Map.of()));
    }

    /**
     * 正则对收款人无能为力的口语句式。句中的「转账」二字足以让规则通道召回到转账卡，
     * 于是模型确实被调用——正是要验的那条路径。
     *
     * <p>原用的是「我要转账给我老板两千」。为场景 5「转一半给张三」新加的
     * {@code PAYEE_AFTER_AMOUNT}（动词与「给」之间容 0-12 字）把那句一并覆盖了，
     * 正则从此能抽到「我老板两」并清理成「老板」；而正则与模型冲突时正则赢
     * （见 {@link #regexWinsOnConflict}），于是那句再也验不到模型补槽这条路径。
     *
     * <p>换成收款人在动词之前且不带「给」的形态：三个正则全不匹配，
     * 「老板」又不是 HanLP 的人名实体（{@code soleEntity} 因此也拿不到），
     * 模型是这个槽位唯一的来源。
     */
    private static final String COLLOQUIAL_TRANSFER = "老板那边转账两千过去";

    @Test
    @DisplayName("正则抽不到的口语化表达，由模型补上")
    void modelFillsWhatRegexCannot() {
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.transfer"],
                 "confidence":0.85,"reasonCode":"CONFIRMATION_REQUIRED",
                 "extractedSlots":{"payee":"我老板","amount":"2000"}}
                """;

        FastPathResult result = decide(json, COLLOQUIAL_TRANSFER);

        assertThat(result.slots()).containsEntry("payee", "我老板").containsEntry("amount", "2000");
    }

    @Test
    @DisplayName("槽位补齐后不再追问，直接进确认")
    void filledSlotsSkipClarification() {
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.transfer"],
                 "confidence":0.85,"reasonCode":"CONFIRMATION_REQUIRED",
                 "extractedSlots":{"payee":"我老板","amount":"2000"}}
                """;

        FastPathResult result = decide(json, COLLOQUIAL_TRANSFER);

        // 回填若发生在 fail-safe 复核之后，这里会是 CLARIFY——模型明明读懂了，系统仍要问一遍
        assertThat(result.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.CONFIRMATION_REQUIRED);
        assertThat(result.decision().missingSlots()).isEmpty();
    }

    @Test
    @DisplayName("正则已抽到的槽位，模型不得覆盖")
    void regexWinsOnConflict() {
        // 模型把金额说成 9999。金额要求同输入同输出，取决于第几次调用是不可接受的
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.transfer"],
                 "confidence":0.9,"reasonCode":"CONFIRMATION_REQUIRED",
                 "extractedSlots":{"payee":"李四","amount":"9999"}}
                """;

        FastPathResult result = decide(json, "给张三转 1000");

        assertThat(result.slots()).containsEntry("payee", "张三").containsEntry("amount", "1000");
    }

    @Test
    @DisplayName("能力卡未声明的槽位，模型填了也丢弃")
    void undeclaredSlotsAreDropped() {
        // cap.transfer 只声明了 payee 与 amount。remark、accountType 领域方从未承认过，
        // 收下它们等于主 Agent 替支付领域定义入参
        String json = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.transfer"],
                 "confidence":0.9,"reasonCode":"CONFIRMATION_REQUIRED",
                 "extractedSlots":{"payee":"张三","amount":"1000","remark":"还钱","accountType":"SAVINGS"}}
                """;

        FastPathResult result = decide(json, "给张三转 1000 备注还钱");

        assertThat(result.slots()).doesNotContainKeys("remark", "accountType");
    }

    /** 网关可用，chat 返回带 extractedSlots 的仲裁结果；语义通道仍不可用。 */
    private record SlotFillingGateway(String chatResponse) implements ModelGatewayClient {

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("stub", 0);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.ok(chatResponse, 1);
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
