package com.huawei.finance.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.ComplianceTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-36 扩展判定：按话题触发的合规提示，触发条件与能力风险等级**并列而非从属**。
 *
 * <p>要守的核心事实是：「买什么基金好」不触发任何 R2 能力，甚至可能一张卡都没命中，
 * 但它落在持牌业务里，必须能触发合规提示（§2.7.9 第一处缺口）。
 * 只要这条通道从属于 riskLevel，这类请求在整条链路上就是隐形的。
 *
 * <p>同时守「留位不启用」：仓库里那份清单是空的，因此**默认行为必须与接入前完全一致**。
 * 这条断言的用途是防止有人顺手填一份工程默认值——口径归业务部与合规（§6 阻断项 2b）。
 */
class ComplianceTopicTest {

    private static AssetBundle realAssets;

    @BeforeAll
    static void load() {
        realAssets = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    @Test
    @DisplayName("仓库里的清单是空的：默认一条话题提示都不该多出来")
    void shippedListIsEmptyOnPurpose() {
        assertThat(realAssets.complianceTopics().getTopics()).isEmpty();

        ResponsePlan plan = plannerOn(realAssets, new SimpleMeterRegistry())
                .plan(context(null, RiskLevel.R0, "买什么基金好"));

        assertThat(plan.riskNoticeCodes()).isEmpty();
    }

    @Test
    @DisplayName("R0 且一张卡都没命中时同样触发：话题通道不从属于能力风险等级")
    void topicFiresWithoutAnyCapability() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResponsePlan plan = plannerOn(withInvestmentTopic(), registry)
                .plan(context(null, RiskLevel.R0, "买什么基金好"));

        assertThat(plan.riskNoticeCodes()).containsExactly("INVESTMENT_ADVISORY");
        assertThat(registry.get(AgentMetrics.COMPLIANCE_TOPIC)
                .tag(AgentMetrics.TAG_REASON, "INVESTMENT_ADVISORY").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("R2 资金类 + 命中话题 → 两条提示都在，顺序稳定")
    void bothSourcesCoexist() {
        ResponsePlan plan = plannerOn(withInvestmentTopic(), new SimpleMeterRegistry())
                .plan(context("cap.transfer", RiskLevel.R2, "转 1000 去买什么基金好"));

        assertThat(plan.riskNoticeCodes()).containsExactly("FUND_MOVEMENT", "INVESTMENT_ADVISORY");
    }

    @Test
    @DisplayName("两条来源给出同一个提示码时只说一遍")
    void duplicateNoticeCodeIsCollapsed() {
        ComplianceTopics topics = new ComplianceTopics();
        ComplianceTopics.Topic topic = new ComplianceTopics.Topic();
        topic.setCode("FUND_MOVEMENT_TOPIC");
        topic.setNoticeCode("FUND_MOVEMENT");
        topic.setKeywords(List.of("转账"));
        topics.setTopics(List.of(topic));

        ResponsePlan plan = plannerOn(bundleWith(topics), new SimpleMeterRegistry())
                .plan(context("cap.transfer", RiskLevel.R2, "我要转账"));

        assertThat(plan.riskNoticeCodes()).containsExactly("FUND_MOVEMENT");
    }

    @Test
    @DisplayName("关键词为空的话题永不触发，而不是恒触发")
    void topicWithoutKeywordsNeverFires() {
        ComplianceTopics topics = new ComplianceTopics();
        ComplianceTopics.Topic halfFilled = new ComplianceTopics.Topic();
        halfFilled.setCode("HALF_FILLED");
        halfFilled.setNoticeCode("SOME_NOTICE");
        topics.setTopics(List.of(halfFilled));

        // 恒触发同样能自圆其说，但那意味着每个用户在每一句话之后都收到一段风险告知，
        // 是能把面客链路直接毁掉的默认值
        assertThat(topics.match("随便说一句")).isEmpty();
    }

    @Test
    @DisplayName("匹配吃的是用户原话，不是任务槽位里的值")
    void matchesTheUserSentenceNotTheSlots() {
        ResponsePlanner planner = plannerOn(withInvestmentTopic(), new SimpleMeterRegistry());

        // 槽位里带着关键词、原话里没有 → 不触发。这条确认了它读的是 userQuery：
        // 改写归一后的文本与领域返回的字段都不能替代用户原本怎么问（同 §5.4 的分工依据）
        ResponsePlan plan = planner.plan(new ResponseContext(
                new com.huawei.finance.common.context.RequestContext("t-1", "s-1", "u-1", "MOBILE_BANK", "home", "", false),
                RouteDecision.builder().decision(Decision.EXECUTE_CAPABILITY)
                        .reasonCode(ReasonCode.HIGH_CONFIDENCE).confidence(0.9).build(),
                null, "task-1", null, GuardrailCheck.passed(),
                Map.of("productName", "买什么基金好"), null, false, "查一下余额", null));

        assertThat(plan.riskNoticeCodes()).isEmpty();
    }

    private static ResponsePlanner plannerOn(AssetBundle bundle, SimpleMeterRegistry registry) {
        return new ResponsePlanner(bundle, new ContractValidator(), new ResponseProperties(), registry);
    }

    private static AssetBundle withInvestmentTopic() {
        ComplianceTopics topics = new ComplianceTopics();
        ComplianceTopics.Topic topic = new ComplianceTopics.Topic();
        topic.setCode("INVESTMENT_ADVICE");
        topic.setNoticeCode("INVESTMENT_ADVISORY");
        topic.setKeywords(List.of("买什么基金", "推荐股票"));
        topics.setTopics(List.of(topic));
        return bundleWith(topics);
    }

    /** 换掉话题清单，其余资产照用真实那份——要验的是编排行为，不是 Freemarker。 */
    private static AssetBundle bundleWith(ComplianceTopics topics) {
        return realAssets.withComplianceTopics(topics);
    }

    private static ResponseContext context(String capabilityId, RiskLevel risk, String userQuery) {
        CapabilityCard card = capabilityId == null ? null : new CapabilityCard(capabilityId, "能力",
                Enums.CapabilityType.TOOL, Enums.Granularity.TOOL, "agent.x", List.of("payment"), "描述",
                List.of(), Map.of(), Map.of(), List.of(), List.of(), risk, 3000,
                Enums.Idempotency.REQUIRED, "owner", "1.0.0", Enums.CapabilityStatus.ACTIVE,
                List.of(), List.of(), List.of(), null);

        return new ResponseContext(
                new com.huawei.finance.common.context.RequestContext("t-1", "s-1", "u-1", "MOBILE_BANK", "home", "", false),
                RouteDecision.builder()
                        .decision(card == null ? Decision.HANDOFF : Decision.EXECUTE_CAPABILITY)
                        .reasonCode(card == null ? ReasonCode.NO_CANDIDATE : ReasonCode.CONFIRMATION_REQUIRED)
                        .confidence(0.9).build(),
                card, "task-1", null, GuardrailCheck.pending(), Map.of(), null, false, userQuery, null);
    }
}
