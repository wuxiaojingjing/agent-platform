package com.huawei.finance.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.model.ResponseComponent;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.TaskResultMetadata;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.ResponsePolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 回复编排 + 渲染。
 *
 * <p>用真实资产：这一层要验的正是线上那批模板与话术能不能覆盖四个出口，
 * 换成测试专用模板就只是在验 Freemarker 本身。
 */
class ResponsePipelineTest {

    private static AssetBundle bundle;
    private static ResponsePlanner planner;
    private static TemplateRenderer renderer;

    @BeforeAll
    static void load() {
        bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
        planner = new ResponsePlanner(bundle, new ContractValidator(), new ResponseProperties(),
                new SimpleMeterRegistry());
        renderer = new TemplateRenderer(bundle, new TemplateVariableValidator(), new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("查余额成功 → 终态模板带上金额与币种")
    void balanceResultRenders() {
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("accountAlias", "尾号 8888 储蓄卡", "availableBalance", "12,345.67"), null, null);

        ResponsePlan plan = planner.plan(context(Decision.EXECUTE_CAPABILITY, ReasonCode.HIGH_CONFIDENCE,
                card("cap.account.balance.query", RiskLevel.R0), result, Map.of()));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.balance.result");
        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(plan.cardComponents()).containsExactly(ResponseComponent.RESULT_SUMMARY);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).contains("12,345.67").contains("¥").contains("尾号 8888 储蓄卡");
    }

    @Test
    @DisplayName("账户明细数组通过模板契约并渲染确定性摘要")
    void transactionResultRendersStructuredRows() {
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("accountAlias", "尾号 8821 借记卡", "transactions", List.of(
                        Map.of("date", "2026-07-24", "description", "超市消费", "amount", "-128.50"),
                        Map.of("date", "2026-07-23", "description", "工资入账", "amount", "+18,600.00"))),
                null, null);

        ResponsePlan plan = planner.plan(context(Decision.EXECUTE_CAPABILITY, ReasonCode.HIGH_CONFIDENCE,
                card("cap.account.transaction.query", RiskLevel.R0), result, Map.of()));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.transaction.result");
        assertThat(plan.cardComponents()).containsExactly(ResponseComponent.RESULT_SUMMARY);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text())
                .contains("尾号 8821 借记卡")
                .contains("2026-07-24 超市消费 -128.50")
                .contains("2026-07-23 工资入账 +18,600.00");
    }

    @Test
    @DisplayName("菜单导航按能力精确读取菜单资产，不进入确认态")
    void navigationRendersDirectlyFromMenuAsset() {
        CapabilityCard navigation = bundle.capability("cap.nav.wealth_product_理财交易记录");
        ResponsePlan plan = planner.plan(context(Decision.NAVIGATION, ReasonCode.HIGH_CONFIDENCE,
                navigation, null, Map.of()));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(plan.templateKey()).isEqualTo("tpl.nav.open");
        assertThat(plan.actionCodes()).containsExactly("OPEN_MENU");
        assertThat(plan.cardComponents()).containsExactly(ResponseComponent.NAVIGATION);
        assertThat(plan.slots())
                .containsEntry("menuName", "理财交易记录")
                .containsEntry("action", "OPEN_MENU");
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).contains("理财交易记录");
    }

    @Test
    @DisplayName("AGENT/GOAL 成功后按目标叶子能力渲染，不把成功事实说成失败")
    void agentGoalRendersWithTargetCapabilityTemplate() {
        TaskResult result = new TaskResult("task-1", Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE,
                Map.of(TaskResultMetadata.TARGET_CAPABILITY_ID, "cap.fund.product.query",
                        "name", "基金产品C", "domain", "基金", "riskLevel", "R2",
                        "returnRate", "3.2%", "term", "灵活"), null, null);

        CapabilityCard agent = bundle.capability("agent.finance_assistant");
        ResponsePlan plan = planner.plan(context(Decision.DELEGATE_GOAL,
                ReasonCode.SHORT_CIRCUIT_STRONG_RULE, agent, result, Map.of()));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.product.result");
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).contains("基金产品C").contains("3.2%");
    }

    @Test
    @DisplayName("转账未执行 → 确认阶段模板，动作码给出确认与取消")
    void transferConfirmRenders() {
        ResponsePlan plan = planner.plan(context(Decision.EXECUTE_CAPABILITY, ReasonCode.CONFIRMATION_REQUIRED,
                card("cap.transfer", RiskLevel.R2), null,
                Map.of("payee", "张三", "amount", "1000",
                        "__context.refreshAtExecution", true)));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.transfer.confirm");
        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(plan.actionCodes()).containsExactly("CONFIRM", "CANCEL");
        assertThat(plan.slots()).doesNotContainKey("__context.refreshAtExecution");
        assertThat(plan.riskNoticeCodes()).contains("FUND_MOVEMENT");
        assertThat(plan.cardComponents()).containsExactly(
                ResponseComponent.RISK_NOTICE, ResponseComponent.REVIEW_SUMMARY);
        assertThat(rendered.text()).contains("张三").contains("1000");
    }

    @Test
    @DisplayName("澄清出口用资产里的话术，不把内部槽位名摆给用户")
    void clarifyUsesConfiguredQuestion() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .missingSlots(List.of("cardType"))
                .confidence(0.7)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision,
                card("cap.card.replace", RiskLevel.R1), "task-1", null, GuardrailCheck.pending(),
                Map.of(), null, false, "换卡", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.clarify.slot");
        assertThat(plan.cardComponents()).containsExactly(ResponseComponent.CHOICE_LIST);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text())
                .startsWith(bundle.clarify().getSlots().get("cardType").getQuestion())
                .contains("信用卡 / 借记卡");
    }

    @Test
    @DisplayName("候选接近时展示业务名称多选，不使用失败话术或内部得分术语")
    void lowMarginRendersBusinessChoices() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.LOW_MARGIN)
                .candidateIds(List.of(
                        "cap.payroll.status.query",
                        "cap.account.card.status.query",
                        "cap.payroll.arrival.query"))
                .confidence(0.36)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision,
                bundle.capability("cap.payroll.status.query"), null, null,
                GuardrailCheck.pending(), Map.of(), null, false, "查询处理结果", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.clarify.slot");
        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(plan.cardComponents()).containsExactly(ResponseComponent.CHOICE_LIST);
        assertThat(plan.slots().get("options")).asList().containsExactly(
                "查询工资代发状态", "查询银行卡状态", "查询工资到账记录");
        assertThat(rendered.text())
                .contains("可以再说明一下吗")
                .doesNotContain("暂时无法完成")
                .doesNotContain("Top1")
                .doesNotContain("cap.");
    }

    @Test
    @DisplayName("跨域且无候选时使用自然澄清，不使用操作失败话术")
    void crossDomainWithoutCandidatesRendersNaturalClarification() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .reasonCode(ReasonCode.CROSS_DOMAIN)
                .confidence(0.36)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision, null, null, null,
                GuardrailCheck.pending(), Map.of(), null, false, "查询处理结果", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.clarify.slot");
        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text())
                .contains("可以再说明一下吗")
                .doesNotContain("暂时无法完成")
                .doesNotContain("Top1");
    }

    @Test
    @DisplayName("多意图 STATIC_PLAN 返回逐项办理引导，而不是沉默")
    void staticPlanReturnsMultiTaskClarify() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.MULTI_INTENT)
                .confidence(0.6)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision, null, null, null,
                GuardrailCheck.pending(), Map.of(), null, false, "查余额再给老徐转 1000", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.clarify.multi-task");
        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).isNotBlank();
    }

    @Test
    @DisplayName("Slow Path 部分完成时展示已完成事实和剩余事项")
    void slowPathPartialResultRendersProgress() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.MULTI_INTENT)
                .confidence(0.9)
                .build();
        TaskResult result = new TaskResult("task-balance", Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE,
                Map.of("capabilities", Map.of("cap.account.balance.query",
                        Map.of("accountAlias", "尾号 8821 借记卡",
                                "availableBalance", "12,845.60"))), null, null);

        ResponsePlan responsePlan = planner.plan(new ResponseContext(ctx(), decision, null,
                "task-balance", result, GuardrailCheck.passed(), Map.of(), null, false,
                "查一下余额，然后查询基金产品C", slowPlan()));
        RenderedResponse rendered = renderer.render(responsePlan);

        assertThat(responsePlan.templateKey()).isEqualTo("tpl.plan.progress");
        assertThat(responsePlan.responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(responsePlan.cardComponents()).containsExactly(
                ResponseComponent.TASK_PROGRESS, ResponseComponent.RESULT_SUMMARY);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).isEqualTo("""
                已查到尾号 8821 借记卡可用余额为 12,845.60 元。
                基金产品查询尚未完成，可继续办理下一项。""");
    }

    @Test
    @DisplayName("Static Plan 到达 R2 步骤时渲染确认而不是错误或自动执行")
    void staticPlanRiskStepRendersConfirmation() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.RESULT_RULE)
                .confidence(1)
                .build();
        CapabilityCard transfer = bundle.capability("cap.transfer");

        ResponsePlan responsePlan = planner.plan(new ResponseContext(ctx(), decision, transfer,
                "task-transfer", null, GuardrailCheck.pending(),
                Map.of("payee", "老徐", "amount", "1000"), null, false,
                "查余额，再给老徐转 1000；不足就别转", slowPlan()));

        assertThat(responsePlan.responsePhase()).isEqualTo(Enums.ResponsePhase.CONFIRM);
        assertThat(responsePlan.templateKey()).isEqualTo("tpl.transfer.confirm");
        assertThat(responsePlan.actionCodes()).containsExactly("CONFIRM", "CANCEL");
        assertThat(responsePlan.cardComponents()).contains(
                ResponseComponent.TASK_PROGRESS,
                ResponseComponent.REVIEW_SUMMARY,
                ResponseComponent.RISK_NOTICE);
    }

    @Test
    @DisplayName("Static Plan 条件无法判定时使用条件澄清模板")
    void staticPlanUndecidedConditionClarifies() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.RESULT_RULE)
                .confidence(1)
                .build();

        ResponsePlan responsePlan = planner.plan(new ResponseContext(ctx(), decision,
                bundle.capability("cap.transfer"), null, null, GuardrailCheck.pending(),
                Map.of("condition", "余额不足就别转", "taskSummary", "转账",
                        "options", List.of("继续办理", "不办理"),
                        "capabilities", Map.of("cap.account.balance.query",
                                Map.of("accountAlias", "尾号 8821 借记卡",
                                        "availableBalance", "12,845.60"))),
                null, false, "", slowPlan()));

        assertThat(responsePlan.responsePhase()).isEqualTo(Enums.ResponsePhase.CLARIFY);
        assertThat(responsePlan.templateKey()).isEqualTo("tpl.clarify.condition");
        assertThat(responsePlan.slots()).containsEntry("options", List.of("继续办理", "不办理"));
        assertThat(responsePlan.cardComponents()).contains(ResponseComponent.RESULT_SUMMARY);
        assertThat(responsePlan.slots()).containsKey("resultCards");
    }

    @Test
    @DisplayName("续办完成时聚合所有 Slow Path 步骤事实")
    void slowPathCompletedResultRendersAggregate() {
        TaskResult result = new TaskResult("task-fund", Enums.TaskStatus.SUCCESS,
                Enums.FailureClass.NONE,
                Map.of("capabilities", Map.of(
                        "cap.account.balance.query",
                        Map.of("accountAlias", "尾号 8821 借记卡",
                                "availableBalance", "12,845.60"),
                        "cap.fund.product.query",
                        Map.of("name", "基金产品C", "riskLevel", "R3",
                                "returnRate", "3.2%", "term", "开放式"))), null, null);

        ResponsePlan responsePlan = planner.plan(new ResponseContext(ctx(),
                RouteDecision.builder().decision(Decision.EXECUTE_CAPABILITY)
                        .reasonCode(ReasonCode.HIGH_CONFIDENCE).confidence(1.0).build(),
                card("cap.fund.product.query", RiskLevel.R0), "task-fund", result,
                GuardrailCheck.passed(), Map.of(), null, false, "继续", slowPlan()));
        RenderedResponse rendered = renderer.render(responsePlan);

        assertThat(responsePlan.templateKey()).isEqualTo("tpl.plan.result");
        assertThat(responsePlan.responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).isEqualTo("""
                两项查询已完成：
                1. 尾号 8821 借记卡可用余额为 12,845.60 元。
                2. 基金产品C风险等级 R3，参考收益 3.2%，开放式。""");
    }

    @Test
    @DisplayName("强规则指定的模板优先，静态变量随规则一起带过来")
    void strongRuleTemplateOverridesDerivedOne() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.HANDOFF)
                .reasonCode(ReasonCode.SHORT_CIRCUIT_STRONG_RULE)
                .confidence(1.0)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision, null, null, null,
                GuardrailCheck.pending(),
                Map.of("capabilityName", "信用卡额度调整", "alternativeEntry", "信用卡 - 额度管理"),
                "tpl.reject.capability-not-open", false, "帮我把信用卡额度改成 10 万", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.reject.capability-not-open");
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).contains("信用卡额度调整").contains("信用卡 - 额度管理");
    }

    /**
     * 标准问答（FP-1I）与拒绝共用一个出口，阶段却必须分开：前者答完了是 FINAL，
     * 后者办不了是 ERROR。判错的样子是用户看到一段正确答案，配着一句「很抱歉」和一个重试按钮。
     */
    @Test
    @DisplayName("标准答案是正常收尾，不是错误态")
    void standardAnswerRendersAsFinal() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.HANDOFF)
                .reasonCode(ReasonCode.STANDARD_ANSWER)
                .confidence(1.0)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision, null, null, null,
                GuardrailCheck.pending(),
                Map.of("answer", "同行转账免费，跨行按每笔 2 元收取。"),
                "tpl.answer.standard", false, "转账手续费怎么算", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.responsePhase()).isEqualTo(Enums.ResponsePhase.FINAL);
        assertThat(plan.actionCodes()).as("没配动作入口就不给按钮，空按钮点不动").isEmpty();
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).isEqualTo("同行转账免费，跨行按每笔 2 元收取。");
    }

    @Test
    @DisplayName("配了动作入口，答完给一个「去办」")
    void standardAnswerCarriesItsAction() {
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.HANDOFF)
                .reasonCode(ReasonCode.STANDARD_ANSWER)
                .confidence(1.0)
                .build();

        ResponsePlan plan = planner.plan(new ResponseContext(ctx(), decision, null, null, null,
                GuardrailCheck.pending(),
                Map.of("answer", "在首页点「转账」即可。", "actionLabel", "去转账",
                        "actionCapabilityId", "cap.transfer"),
                "tpl.answer.standard", false, "怎么转账", null));

        assertThat(plan.actionCodes()).containsExactly("OPEN_CAPABILITY");
        assertThat(renderer.render(plan).text()).contains("在首页点").contains("去转账");
    }

    @Test
    @DisplayName("护栏拒绝 → 说明原因，不暴露护栏码")
    void guardrailRejectionExplainsInPlainWords() {
        ResponsePlan plan = planner.plan(new ResponseContext(ctx(),
                RouteDecision.builder().decision(Decision.EXECUTE_CAPABILITY)
                        .reasonCode(ReasonCode.HIGH_CONFIDENCE).confidence(0.9).build(),
                card("cap.transfer", RiskLevel.R2), "task-1", null,
                GuardrailCheck.failed(List.of("AMOUNT_LIMIT_EXCEEDED")), Map.of(), null, false,
                "给张三转 50 万", null));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.reject.guardrail");
        assertThat(rendered.text()).contains("超出单笔限额").doesNotContain("AMOUNT_LIMIT_EXCEEDED");
    }

    /**
     * FP-26a 的回复侧：领域调用超时后，有副作用的能力**不得引导重试**。
     *
     * <p>超时并没有撤回那笔已经发出去的转账。回一句「办理失败，请重试」是在诱导用户再转一次；
     * 幂等键挡得住经由本系统的重放，挡不住用户重新发起。
     */
    @Test
    @DisplayName("结果未知（PARTIAL）时不说失败、不给重试引导")
    void uncertainOutcomeDoesNotInviteRetry() {
        TaskResult timedOut = new TaskResult("task-1", Enums.TaskStatus.FAILED,
                Enums.FailureClass.PARTIAL, Map.of("error", "TIMEOUT"), "idem-key-0001", null);

        ResponsePlan plan = planner.plan(context(Decision.EXECUTE_CAPABILITY, ReasonCode.CONFIRMATION_REQUIRED,
                card("cap.transfer", RiskLevel.R2), timedOut, Map.of()));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.fallback.uncertain");
        assertThat(plan.actionCodes()).doesNotContain("RETRY");
        assertThat(plan.actionCodes()).contains("CHECK_DETAIL");
        assertThat(rendered.fellBack()).isFalse();
        assertThat(rendered.text()).doesNotContain("失败");
    }

    @Test
    @DisplayName("其余失败仍走通用兜底并允许重试")
    void ordinaryFailureStillOffersRetry() {
        TaskResult failed = new TaskResult("task-1", Enums.TaskStatus.FAILED,
                Enums.FailureClass.RETRYABLE, Map.of("error", "TIMEOUT"), null, null);

        ResponsePlan plan = planner.plan(context(Decision.EXECUTE_CAPABILITY, ReasonCode.HIGH_CONFIDENCE,
                card("cap.account.balance.query", RiskLevel.R0), failed, Map.of()));

        assertThat(plan.templateKey()).isEqualTo("tpl.fallback.generic");
        assertThat(plan.actionCodes()).contains("RETRY");
    }

    @Test
    @DisplayName("变量缺失时走兜底，绝不渲染出「余额为 元」这种句子")
    void missingVariableFallsBackInsteadOfRenderingBlank() {
        // 领域 Agent 少返了 availableBalance：Freemarker 会把它渲染成空串，
        // 语句通顺但数字消失，这正是变量校验要拦下的情形
        TaskResult broken = new TaskResult("task-1", Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                Map.of("accountAlias", "尾号 8888 储蓄卡"), null, null);

        ResponsePlan plan = planner.plan(context(Decision.EXECUTE_CAPABILITY, ReasonCode.HIGH_CONFIDENCE,
                card("cap.account.balance.query", RiskLevel.R0), broken, Map.of()));
        RenderedResponse rendered = renderer.render(plan);

        assertThat(plan.templateKey()).isEqualTo("tpl.balance.result");
        assertThat(rendered.fellBack()).isTrue();
        assertThat(rendered.reason()).isEqualTo("variables-invalid");
        assertThat(rendered.usedTemplateKey()).isEqualTo("tpl.fallback.generic");
        assertThat(rendered.text()).isNotBlank().doesNotContain("尾号 8888");
    }

    @Test
    @DisplayName("模板键不存在时兜底，并且兜底只走一层")
    void unknownTemplateFallsBackOnce() {
        ResponsePlan plan = ResponsePlan.builder()
                .traceId("trace-1")
                .sceneCode("unknown#EXECUTE_CAPABILITY")
                .responsePhase(Enums.ResponsePhase.FINAL)
                .templateKey("tpl.does.not.exist")
                .templateVersion("unknown")
                .renderMode(Enums.RenderMode.TEMPLATE)
                .slots(Map.of())
                .channel("MOBILE_BANK")
                .fallbackTemplateKey("tpl.fallback.generic")
                .build();

        RenderedResponse rendered = renderer.render(plan);

        assertThat(rendered.fellBack()).isTrue();
        assertThat(rendered.reason()).isEqualTo("template-not-found");
        assertThat(rendered.usedTemplateKey()).isEqualTo("tpl.fallback.generic");
    }

    @Test
    @DisplayName("资产整体不可用时仍能给出一句话")
    void lastResortTextWhenFallbackTemplateMissing() {
        ResponsePlan plan = ResponsePlan.builder()
                .traceId("trace-1")
                .sceneCode("unknown#EXECUTE_CAPABILITY")
                .responsePhase(Enums.ResponsePhase.ERROR)
                .templateKey("tpl.does.not.exist")
                .templateVersion("unknown")
                .renderMode(Enums.RenderMode.TEMPLATE)
                .slots(Map.of())
                .channel("MOBILE_BANK")
                .fallbackTemplateKey("tpl.also.missing")
                .build();

        RenderedResponse rendered = renderer.render(plan);

        assertThat(rendered.fellBack()).isTrue();
        assertThat(rendered.usedTemplateKey()).isNull();
        assertThat(rendered.text()).isNotBlank();
    }

    @ParameterizedTest(name = "Loop 回复策略支持 {0}")
    @EnumSource(Enums.RenderMode.class)
    @DisplayName("Loop Runtime 回复与普通出口共用四种回复策略")
    void loopRuntimeResponseResolvesEveryRenderMode(Enums.RenderMode mode) {
        ResponsePolicy policy = new ResponsePolicy();
        policy.setVersion("loop-policy-v2");
        policy.setPromptVersion("loop-response-v2");
        ResponsePolicy.Rule rule = new ResponsePolicy.Rule();
        rule.setAgent("agent.mobile-banking-assistant");
        rule.setScene("AGENT_LOOP#COMPLETED");
        rule.setPhase("FINAL");
        rule.setMode(mode);
        rule.setModel("response-model");
        rule.setTemplateSet(List.of("tpl.loop.final"));
        rule.setTemperature(0.2);
        rule.setMaxTokens(320);
        policy.setRules(List.of(rule));

        ResponsePlanner loopPlanner = new ResponsePlanner(bundle.withResponsePolicy(policy),
                new ContractValidator(), new ResponseProperties(), new SimpleMeterRegistry());
        ResponsePlan plan = loopPlanner.planRuntimeResponse(ctx(), "loop-1",
                "agent.mobile-banking-assistant", "AGENT_LOOP#COMPLETED",
                Enums.ResponsePhase.FINAL, "tpl.loop.final",
                Map.of("summary", "工资到账情况已检查", "reason", "FINISHED"), List.of());

        assertThat(plan.renderMode()).isEqualTo(mode);
        assertThat(plan.responseModel()).isEqualTo("response-model");
        assertThat(plan.approvedTemplateKeys()).containsExactly("tpl.loop.final");
        assertThat(plan.responsePolicyVersion()).isEqualTo("loop-policy-v2");
        assertThat(plan.responsePromptVersion()).isEqualTo("loop-response-v2");
        assertThat(plan.actionCodes()).isEmpty();
        assertThat(plan.cardComponents()).containsExactly(ResponseComponent.LOOP_STATUS);
    }

    private static ResponseContext context(Decision decision, ReasonCode reason, CapabilityCard card,
                                           TaskResult result, Map<String, Object> slots) {
        return new ResponseContext(ctx(),
                RouteDecision.builder().decision(decision).reasonCode(reason)
                        .candidateIds(List.of(card.capabilityId())).confidence(0.9).build(),
                card, "task-1", result, GuardrailCheck.passed(), slots, null, false, "查一下余额", null);
    }

    private static RequestContext ctx() {
        return new RequestContext("trace-1", "s-1", "u-1", "MOBILE_BANK", "home", "", false);
    }

    private static IntentPlan slowPlan() {
        return new IntentPlan("查一下余额，然后查询基金产品C", List.of(
                new SubIntent(0, "查一下余额", "cap.account.balance.query", "查询账户余额",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked("cap.account.balance.query", "test:fixture")),
                new SubIntent(1, "查询基金产品C", "cap.fund.product.query", "查询基金产品C",
                        Enums.IntentRelation.SEQUENTIAL, null,
                        PlanResolution.locked("cap.fund.product.query", "test:fixture"))),
                IntentPlan.Source.RULE);
    }

    private static CapabilityCard card(String capabilityId, RiskLevel risk) {
        return new CapabilityCard(capabilityId, "能力", Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.x", List.of("account"), "描述", List.of(), Map.of(), Map.of(), List.of(), List.of(),
                risk, 3000, Enums.Idempotency.NONE, "owner", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(), null);
    }
}
