package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.StandardAnswer;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.StandardQaBank;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 标准问答直答（FP-1I 小 i 共存）。
 *
 * <p>这一级短路的位置是它全部的风险所在：早一步会盖掉策略拦截，晚一步就白花了模型的钱。
 * 所以这里验的不只是「命中能答」，更多是**什么时候不该由它答**。
 *
 * <p>样例条目写在用例里而不是生产资产里：标准问答的口径归业务与客服，
 * 工程往 {@code assets/standard-qa.yaml} 里填「示例」会让人以为知识问答已经覆盖了。
 */
class StandardAnswerTest {

    private static final String FEE_ANSWER = "同行转账免费，跨行按每笔 2 元收取。";

    private static FastPathFixture.Built fixtureWith(StandardQaBank qa) {
        AssetBundle bundle = FastPathFixture.assets().withStandardQa(qa);
        return FastPathFixture.build(bundle, new FastPathFixture.UnavailableGateway(),
                CandidateSearch.unavailable());
    }

    private static StandardQaBank bankOf(StandardQaBank.Entry... entries) {
        StandardQaBank bank = new StandardQaBank();
        bank.setItems(List.of(entries));
        return bank;
    }

    private static StandardQaBank.Entry entry(String id, String question, List<String> patterns,
                                              String answer) {
        StandardQaBank.Entry entry = new StandardQaBank.Entry();
        entry.setId(id);
        entry.setQuestion(question);
        entry.setPatterns(patterns);
        entry.setAnswer(answer);
        return entry;
    }

    private static FastPathRequest ask(String query) {
        return new FastPathRequest(
                new RequestContext("trace-qa", "s-qa", "u-1", "MOBILE_BANK", "home", "", false),
                query, null, Map.of());
    }

    @Test
    @DisplayName("句法模版命中标准问，直接念标准答案")
    void matchedQuestionAnswersDirectly() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("[请问]转账[的]手续费怎么{算|收}"), FEE_ANSWER)));

        FastPathResult result = fixture.engine().decide(ask("请问转账的手续费怎么收"));

        assertThat(result.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.STANDARD_ANSWER_RULE);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.STANDARD_ANSWER);
        assertThat(result.templateKey()).isEqualTo(StandardAnswer.TEMPLATE_KEY);
        assertThat(result.slots()).containsEntry(StandardAnswer.SLOT_ANSWER, FEE_ANSWER);
        assertThat(result.decision().evidenceRefs())
                .as("出口要能追回是哪一条标准问答答的，否则改错一条无从定位")
                .contains("standardQa:qa.transfer.fee");
    }

    @Test
    @DisplayName("审批知识把来源引用随出口透传，答案与截图证据可追溯")
    void approvedKnowledgePropagatesSourceReference() {
        StandardQaBank.Entry sourced = entry("qa.branch.directory", "网点服务有哪些功能",
                List.of("网点服务有哪些功能"), "包括预约取号等功能。");
        sourced.setSourceRef("screenshotLedger:mobile-banking-business-directory:image-4");

        FastPathResult result = fixtureWith(bankOf(sourced)).engine()
                .decide(ask("网点服务有哪些功能"));

        assertThat(result.decision().evidenceRefs()).containsExactly(
                "standardQa:qa.branch.directory",
                "screenshotLedger:mobile-banking-business-directory:image-4");
    }

    @Test
    @DisplayName("标准答案不建任务，也不选中任何能力")
    void standardAnswerSelectsNoCapability() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER)));

        FastPathResult result = fixture.engine().decide(ask("转账手续费怎么算"));

        assertThat(result.decision().selectedCandidateId())
                .as("答一句话不该指向任何能力，指了中控就会去建档")
                .isNull();
    }

    /**
     * 召回照跑是这条链路的成本代价，也是它唯一的自查手段：一条写宽了的标准问会吃掉
     * 本该走能力的流量，而只看出口分布的话，那只表现为「知识问答涨了」。
     */
    @Test
    @DisplayName("召回照常跑完，候选集留在 trace 上，才查得出这条模版抢了谁的活")
    void recallStillRunsAndIsRecorded() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER)));

        FastPathResult result = fixture.engine().decide(ask("转账手续费怎么算"));

        assertThat(result.recall()).as("召回结果要随出口一起交出去").isNotNull();
        assertThat(fixture.trace().phaseNanos).containsKey("recall");
    }

    @Test
    @DisplayName("省下的是模型那一次：直出不问仲裁")
    void noModelRoundTrip() {
        CountingGateway gateway = new CountingGateway();
        AssetBundle bundle = FastPathFixture.assets().withStandardQa(bankOf(entry("qa.transfer.fee",
                "转账手续费怎么算", List.of("转账手续费怎么算"), FEE_ANSWER)));

        FastPathFixture.Built fixture =
                FastPathFixture.build(bundle, gateway, CandidateSearch.unavailable());
        fixture.engine().decide(ask("转账手续费怎么算"));

        assertThat(gateway.chatCalls)
                .as("答案是人写死的，再问一次模型既花钱又给了它把出口带偏的机会")
                .isZero();
    }

    /**
     * 出口缓存里只有 {@code RouteDecision}，装不下答案正文与动作入口。写进去的话，
     * 第二次命中缓存拿回来的是一个没有答案的标准答案出口，渲染出来是一句兜底话术——
     * 而缓存 TTL 是十分钟，同一句话在这十分钟里次次如此。强规则不写缓存也是同一个理由。
     */
    @Test
    @DisplayName("标准答案不进出口缓存，否则第二次的答案就没了")
    void standardAnswerIsNotCached() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER)));

        FastPathResult first = fixture.engine().decide(ask("转账手续费怎么算"));
        FastPathResult second = fixture.engine().decide(ask("转账手续费怎么算"));

        assertThat(second.decision().shortCircuit())
                .as("走了缓存就说明答案被扔进了一个装不下它的地方")
                .isEqualTo(first.decision().shortCircuit())
                .isEqualTo(ShortCircuitLevel.STANDARD_ANSWER_RULE);
        assertThat(second.slots()).containsEntry(StandardAnswer.SLOT_ANSWER, FEE_ANSWER);
    }

    @Test
    @DisplayName("策略拦截压过标准问答：本期未开放的事不能被答成一段说明")
    void policyRuleWinsOverStandardAnswer() {
        // 这条模版故意去抢强规则已经拦下的那句话
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.limit", "信用卡额度怎么调",
                List.of("信用卡额度怎么{调|改}"), "登录后在信用卡菜单里自助调整。")));

        FastPathResult result = fixture.engine().decide(ask("信用卡额度怎么调"));

        assertThat(result.decision().shortCircuit())
                .as("强规则在前，标准问答在后。反过来的话，一句「未开放」会被答成一段操作指引")
                .isEqualTo(ShortCircuitLevel.L2_STRONG_RULE);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
    }

    @Test
    @DisplayName("没命中就当它不存在，照常走召回与仲裁")
    void unmatchedQuestionFallsThrough() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER)));

        FastPathResult result = fixture.engine().decide(ask("查一下余额"));

        assertThat(result.decision().shortCircuit()).isNotEqualTo(ShortCircuitLevel.STANDARD_ANSWER_RULE);
        assertThat(result.recall()).as("没命中就该照常召回").isNotNull();
    }

    @Test
    @DisplayName("答案缺变量就不命中：宁可走正常链路，也不念一句带空洞的话")
    void missingSlotSkipsTheEntry() {
        StandardQaBank.Entry entry = entry("qa.balance.echo", "我的余额是多少",
                List.of("我的余额是多少"), "您的余额是 ${amount} 元。");
        entry.setRequiredSlots(List.of("amount"));

        FastPathResult result = fixtureWith(bankOf(entry)).engine().decide(ask("我的余额是多少"));

        assertThat(result.decision().reasonCode()).isNotEqualTo(ReasonCode.STANDARD_ANSWER);
    }

    @Test
    @DisplayName("配了动作入口才给按钮，没配不给")
    void actionEntryIsOptional() {
        StandardQaBank.Entry withAction = entry("qa.transfer.how", "怎么转账",
                List.of("怎么转账"), "在首页点「转账」即可。");
        withAction.setActionCapabilityId("cap.transfer");
        withAction.setActionLabel("去转账");

        FastPathResult acted = fixtureWith(bankOf(withAction)).engine().decide(ask("怎么转账"));
        assertThat(acted.slots())
                .containsEntry(StandardAnswer.SLOT_ACTION_CAPABILITY, "cap.transfer")
                .containsEntry(StandardAnswer.SLOT_ACTION_LABEL, "去转账");

        FastPathResult plain = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER))).engine().decide(ask("转账手续费怎么算"));
        assertThat(plain.slots()).doesNotContainKey(StandardAnswer.SLOT_ACTION_CAPABILITY);
    }

    @Test
    @DisplayName("知识直答不携带未声明的通用抽槽，避免污染后续任务")
    void standardAnswerKeepsOnlyDeclaredSlots() {
        StandardQaBank.Entry care = entry("qa.card.care", "换卡无忧怎么开通",
                List.of("换卡无忧怎么开通"), "请到营业网点办理。");

        FastPathResult result = fixtureWith(bankOf(care)).engine().decide(ask("换卡无忧怎么开通"));

        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.STANDARD_ANSWER);
        assertThat(result.slots())
                .containsEntry(StandardAnswer.SLOT_ANSWER, "请到营业网点办理。")
                .doesNotContainKey("payee");
    }

    @Test
    @DisplayName("小 i 来源阻断条目只给安全选项，不建任务也不进入相邻能力")
    void blockedKnowledgeOffersSafeChoicesWithoutSelectingCapability() {
        StandardQaBank.Entry blocked = entry("qa.card.same-number", "银行卡换卡不换号",
                List.of("[银行卡]换卡不换号[怎么办]"), "");
        blocked.setStatus(StandardQaBank.Entry.Status.BLOCKED_SOURCE_REVIEW);
        blocked.setGuidance("当前没有经过复核的统一口径，请选择下一步。");
        blocked.setOptions(List.of("了解相关知识", "办理换卡"));
        blocked.setSourceRef("xiaoiLedger:question:15");

        FastPathResult result = fixtureWith(bankOf(blocked)).engine()
                .decide(ask("银行卡换卡不换号怎么办"));

        assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
        assertThat(result.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.STANDARD_ANSWER_RULE);
        assertThat(result.decision().selectedCandidateId()).isNull();
        assertThat(result.decision().evidenceRefs()).containsExactly(
                "standardQaBlocked:qa.card.same-number", "xiaoiLedger:question:15");
        assertThat(result.templateKey()).isEqualTo("tpl.clarify.slot");
        assertThat(result.slots())
                .containsEntry("question", "当前没有经过复核的统一口径，请选择下一步。")
                .containsEntry("options", List.of("了解相关知识", "办理换卡"))
                .doesNotContainKey("payee");
    }

    @Test
    @DisplayName("按条打点：一条模版吃掉大半流量，只有分条的曲线看得出来")
    void hitsAreCountedPerEntry() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER)));

        fixture.engine().decide(ask("转账手续费怎么算"));

        assertThat(fixture.meterRegistry().counter("huawei.finance.agent.standard_answer.hit", "qaId", "qa.transfer.fee")
                .count()).isEqualTo(1.0);
    }

    /** 只数仲裁那一次往返。embedding 走不走取决于索引就绪与否，那不是这条用例要管的事。 */
    private static final class CountingGateway implements com.huawei.finance.gateway.ModelGatewayClient {

        private int chatCalls;

        @Override
        public com.huawei.finance.gateway.GatewayResult<List<float[]>> embed(List<String> inputs) {
            return com.huawei.finance.gateway.GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public com.huawei.finance.gateway.GatewayResult<String> chat(com.huawei.finance.gateway.ChatRequest request) {
            chatCalls++;
            return com.huawei.finance.gateway.GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public com.huawei.finance.gateway.GatewayResult<List<com.huawei.finance.gateway.RerankHit>> rerank(
                String query, List<String> documents, int topN) {
            return com.huawei.finance.gateway.GatewayResult.unavailable("no-gateway", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    @Test
    @DisplayName("标准问答使用独立 DIRECT_KNOWLEDGE 出口")
    void standardAnswerUsesKnowledgeDecision() {
        FastPathFixture.Built fixture = fixtureWith(bankOf(entry("qa.transfer.fee", "转账手续费怎么算",
                List.of("转账手续费怎么算"), FEE_ANSWER)));

        FastPathResult result = fixture.engine().decide(ask("转账手续费怎么算"));

        assertThat(result.decision().decision()).isEqualTo(Decision.DIRECT_KNOWLEDGE);
    }
}
