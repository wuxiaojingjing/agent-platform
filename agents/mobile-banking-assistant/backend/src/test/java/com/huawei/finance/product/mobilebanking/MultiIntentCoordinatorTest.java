package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.orchestrator.plan.IntentPlanRepository;
import com.huawei.finance.orchestrator.plan.PlanRecord;
import com.huawei.finance.orchestrator.plan.PlanState;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.intent.ConditionEvaluator;
import com.huawei.finance.intent.IntentPlanner;
import com.huawei.finance.intent.SlowPathProperties;
import com.huawei.finance.runtime.multi.MultiIntentCoordinator;
import com.huawei.finance.runtime.spi.SessionAffinityPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 协调器对慢路径开关与规划器失败的行为。
 *
 * <p>不打真模型、不落库：这里守的是「开不开、失败退不退」，不是规划质量。
 */
class MultiIntentCoordinatorTest {

    private static final CapabilityCard BALANCE = card("cap.balance", "余额查询");
    private static final CapabilityCard TRANSFER = card("cap.transfer", "转账");

    @Test
    @DisplayName("enabled=false 时不调规划器，直接用 RULE 计划")
    void disabledKeepsRulePlanWithoutCallingPlanner() {
        AtomicBoolean called = new AtomicBoolean();
        IntentPlanner planner = (goal, candidates, ruleFallback) -> {
            called.set(true);
            throw new AssertionError("开关关着还去调规划器");
        };
        RecordingPlans plans = new RecordingPlans();

        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(false);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, planner, new ConditionEvaluator(), props, SessionAffinityPort.NONE);

        IntentPlan rule = rulePlan();
        IntentPlan opened = coordinator.openIfMultiIntent(
                bundle(), ctx(), multiIntentDecision(), rule);

        assertThat(called).isFalse();
        assertThat(opened.source()).isEqualTo(IntentPlan.Source.RULE);
        assertThat(plans.opened).containsExactly(new OpenCall("agent.entry", "s-1", "t-1", rule));
    }

    @Test
    @DisplayName("enabled=true 时用规划器精化，规则已有选择的计划来源为 HYBRID")
    void enabledUsesPlannerResult() {
        IntentPlan plannerPlan = new IntentPlan(rulePlan().original(), rulePlan().items(),
                IntentPlan.Source.PLANNER);
        IntentPlanner planner = (goal, candidates, ruleFallback) -> Optional.of(plannerPlan);
        RecordingPlans plans = new RecordingPlans();

        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(true);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, planner, new ConditionEvaluator(), props, SessionAffinityPort.NONE);

        IntentPlan opened = coordinator.openIfMultiIntent(
                bundle(), ctx(), multiIntentDecision(), rulePlan());

        assertThat(opened.source()).isEqualTo(IntentPlan.Source.HYBRID);
        assertThat(opened.items().get(1).condition()).isEqualTo("不足就别转");
        assertThat(plans.opened).containsExactly(
                new OpenCall("agent.entry", "s-1", "t-1", opened));
    }

    @Test
    @DisplayName("全部步骤已锁定时不调用模型，直接落 RULE 计划")
    void allLockedSkipsPlanner() {
        IntentPlanner planner = (goal, candidates, fallback) -> {
            throw new AssertionError("全锁定计划不应调用模型");
        };
        RecordingPlans plans = new RecordingPlans();
        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(true);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, planner, new ConditionEvaluator(), props, SessionAffinityPort.NONE);
        IntentPlan locked = new IntentPlan("查余额，然后查基金", List.of(
                new SubIntent(0, "查余额", "cap.balance", "余额查询",
                        Enums.IntentRelation.PARALLEL, null,
                        new PlanResolution(PlanResolution.Strength.LOCKED, 1.0, 1.0,
                                List.of("cap.balance"), List.of("utterance:查余额"))),
                new SubIntent(1, "查基金", "cap.transfer", "转账",
                        Enums.IntentRelation.SEQUENTIAL, null,
                        new PlanResolution(PlanResolution.Strength.LOCKED, 0.8, 0.5,
                                List.of("cap.transfer"), List.of("keyword:基金")))),
                IntentPlan.Source.RULE);

        IntentPlan opened = coordinator.openIfMultiIntent(
                bundle(), ctx(), multiIntentDecision(), locked);

        assertThat(opened).isSameAs(locked);
        assertThat(plans.opened).containsExactly(new OpenCall("agent.entry", "s-1", "t-1", locked));
    }

    @Test
    @DisplayName("enabled=true 但规划器回退 RULE 时仍开 RULE 计划")
    void plannerFailureStillOpensRulePlan() {
        // 协调器吃的是 IntentPlanner.plan 的 Optional；ReActIntentPlanner 内部吞异常后
        // 返回 RULE。这里直接模拟那条失败形态
        IntentPlanner fallingBack = (goal, candidates, ruleFallback) -> Optional.of(ruleFallback);
        RecordingPlans plans = new RecordingPlans();

        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(true);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, fallingBack, new ConditionEvaluator(), props, SessionAffinityPort.NONE);

        IntentPlan rule = rulePlan();
        IntentPlan opened = coordinator.openIfMultiIntent(
                bundle(), ctx(), multiIntentDecision(), rule);

        assertThat(opened.source()).isEqualTo(IntentPlan.Source.RULE);
        assertThat(plans.opened).containsExactly(new OpenCall("agent.entry", "s-1", "t-1", rule));
    }

    @Test
    @DisplayName("非多意图出口不落计划、不调规划器")
    void nonMultiIntentDoesNothing() {
        IntentPlanner planner = (goal, candidates, fallback) -> {
            throw new AssertionError("非多意图不应调用规划器");
        };
        RecordingPlans plans = new RecordingPlans();
        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(true);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, planner, new ConditionEvaluator(), props, SessionAffinityPort.NONE);

        RouteDecision fast = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .build();

        assertThat(coordinator.openIfMultiIntent(bundle(), ctx(), fast, rulePlan())).isNull();
        assertThat(plans.opened).isEmpty();
    }

    @Test
    @DisplayName("CROSS_DOMAIN 对比计划返回但不落 pick-one 游标")
    void crossDomainCompareReturnsPlanWithoutPersisting() {
        IntentPlanner planner = (goal, candidates, fallback) -> {
            throw new AssertionError("CROSS_DOMAIN 对比不应调用 pick-one 规划器");
        };
        RecordingPlans plans = new RecordingPlans();
        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(true);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, planner, new ConditionEvaluator(), props, SessionAffinityPort.NONE);

        IntentPlan compare = new IntentPlan("对比基金产品C和保险产品D", List.of(
                new SubIntent(0, "查询产品C", "cap.fund.product.query", "查询基金产品C",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked("cap.fund.product.query",
                                "catalog:product-compare:test-fixture")),
                new SubIntent(1, "查询产品D", "cap.insurance.product.query", "查询保险产品D",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked("cap.insurance.product.query",
                                "catalog:product-compare:test-fixture"))),
                IntentPlan.Source.RULE);

        RouteDecision cross = RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.CROSS_DOMAIN)
                .build();

        IntentPlan opened = coordinator.openIfSlowPath(bundle(), ctx(), cross, compare);

        assertThat(opened).isSameAs(compare);
        assertThat(plans.opened).isEmpty();
    }

    @Test
    @DisplayName("RESULT_RULE 条件计划必须落 Static Plan，不能退回无运行态的降级回复")
    void resultRuleOpensStaticPlan() {
        RecordingPlans plans = new RecordingPlans();
        SlowPathProperties props = new SlowPathProperties();
        props.setEnabled(false);
        MultiIntentCoordinator coordinator = new MultiIntentCoordinator(
                plans, (goal, candidates, fallback) -> Optional.of(fallback),
                new ConditionEvaluator(), props, SessionAffinityPort.NONE);
        RouteDecision resultRule = RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.RESULT_RULE)
                .build();

        IntentPlan opened = coordinator.openIfSlowPath(bundle(), ctx(), resultRule, rulePlan());

        assertThat(opened).isEqualTo(rulePlan());
        assertThat(plans.opened).containsExactly(new OpenCall("agent.entry", "s-1", "t-1", rulePlan()));
    }

    private static RequestContext ctx() {
        return new RequestContext("t-1", "s-1", "u-1", "MOBILE_BANK", "home", "", false);
    }

    private static RouteDecision multiIntentDecision() {
        return RouteDecision.builder()
                .decision(Decision.STATIC_PLAN)
                .reasonCode(ReasonCode.MULTI_INTENT)
                .build();
    }

    private static IntentPlan rulePlan() {
        return new IntentPlan("查余额，再给老徐转 1000；不足就别转", List.of(
                new SubIntent(0, "查余额", "cap.balance", "余额查询",
                        Enums.IntentRelation.PARALLEL, null,
                        preferred("cap.balance")),
                new SubIntent(1, "给老徐转 1000", "cap.transfer", "转账",
                        Enums.IntentRelation.CONDITIONAL, "不足就别转",
                        preferred("cap.transfer"))),
                IntentPlan.Source.RULE);
    }

    private static PlanResolution preferred(String capabilityId) {
        return new PlanResolution(PlanResolution.Strength.PREFERRED, 0.4, 0.1,
                List.of(capabilityId), List.of("test:fixture"));
    }

    private static AssetBundle bundle() {
        return new AssetBundle(
                "v", "v", List.of(BALANCE, TRANSFER), List.of(), List.of(),
                null, null, null, Map.of(), Map.of(), null, null, null, null, null);
    }

    private static CapabilityCard card(String id, String name) {
        return new CapabilityCard(id, name, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                null, List.of("bank"), name, List.of(), Map.of(), Map.of(), List.of(), List.of(),
                RiskLevel.R0, 3000, Enums.Idempotency.REQUIRED, "owner", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(), null);
    }

    private record OpenCall(String agentId, String sessionId, String traceId, IntentPlan plan) {
    }

    private static final class RecordingPlans implements IntentPlanRepository {
        private final java.util.ArrayList<OpenCall> opened = new java.util.ArrayList<>();

        @Override
        public PlanRecord open(String agentId, String sessionId, String traceId, IntentPlan plan) {
            opened.add(new OpenCall(agentId, sessionId, traceId, plan));
            return new PlanRecord("plan-1", agentId, sessionId, traceId,
                    plan, 0, PlanState.IN_PROGRESS);
        }

        @Override
        public Optional<PlanRecord> findActiveBySession(String agentId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public boolean advance(String planId, int from) {
            return false;
        }

        @Override
        public void abandonActive(String agentId, String sessionId, String reason) {
        }
    }
}
