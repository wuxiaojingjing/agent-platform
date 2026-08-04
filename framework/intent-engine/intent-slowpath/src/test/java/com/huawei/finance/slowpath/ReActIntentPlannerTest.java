package com.huawei.finance.slowpath;

import com.huawei.finance.intent.IntentPlanner;
import com.huawei.finance.intent.SlowPathProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.SubIntent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 规划器的回退与条件承接。
 *
 * <p>不打真模型：这里要守的是「模型不可用或结果不如规则时会发生什么」，
 * 而那恰恰是模型不参与的那条分支。真实规划质量由评测负责，不由单测负责。
 */
class ReActIntentPlannerTest {

    private static final CapabilityCard BALANCE = card("cap.balance", "余额查询", RiskLevel.R0);
    private static final CapabilityCard TRANSFER = card("cap.transfer", "转账", RiskLevel.R2);

    /** 直接喂提案，跳过 ReAct 循环——被测的是折叠逻辑。 */
    private static Optional<IntentPlan> fold(List<TaskProposal> proposals, IntentPlan fallback) {
        return new StubPlanner(proposals).plan("查余额再转账", List.of(BALANCE, TRANSFER), fallback);
    }

    private static final class StubPlanner extends ReActIntentPlanner {
        private final List<TaskProposal> proposals;

        private StubPlanner(List<TaskProposal> proposals) {
            super(new SlowPathProperties());
            this.proposals = proposals;
        }

        @Override
        protected List<TaskProposal> propose(String goal, List<CapabilityCard> candidates) {
            return proposals;
        }
    }

    private static IntentPlan rulePlanWithCondition() {
        return new IntentPlan("查余额，再给老徐转 1000；不足就别转", List.of(
                new SubIntent(0, "查余额", "cap.balance", "余额查询",
                        Enums.IntentRelation.PARALLEL, null,
                        PlanResolution.locked("cap.balance", "test:fixture")),
                new SubIntent(1, "给老徐转 1000", "cap.transfer", "转账",
                        Enums.IntentRelation.CONDITIONAL, "不足就别转",
                        PlanResolution.locked("cap.transfer", "test:fixture"))),
                IntentPlan.Source.RULE);
    }

    @Test
    @DisplayName("没有规则步骤锚点时不进行无约束规划")
    void missingRuleAnchorIsRejected() {
        assertThatThrownBy(() -> fold(List.of(
                new TaskProposal(BALANCE, Map.of(), "先看余额够不够"),
                new TaskProposal(TRANSFER, Map.of("amount", "1000"), "再转账")), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("规则步骤锚点");
    }

    @Test
    @DisplayName("规则认出的条件要带过来：换上模型规划不能让「不足就别转」凭空消失")
    void conditionSurvivesTheUpgrade() {
        IntentPlan plan = fold(List.of(
                new TaskProposal(BALANCE, Map.of(), "查余额"),
                new TaskProposal(TRANSFER, Map.of(), "转账")), rulePlanWithCondition()).orElseThrow();

        SubIntent transfer = plan.items().get(1);
        assertThat(transfer.relation()).isEqualTo(Enums.IntentRelation.CONDITIONAL);
        assertThat(transfer.condition()).isEqualTo("不足就别转");
    }

    @Test
    @DisplayName("Planner 只折叠提案；错序结果交给 Runtime Grounding 统一拒绝")
    void reorderedProposalIsLeftForRuntimeGrounding() {
        IntentPlan plan = fold(List.of(
                new TaskProposal(TRANSFER, Map.of(), "转账"),
                new TaskProposal(BALANCE, Map.of(), "查余额")), rulePlanWithCondition()).orElseThrow();

        assertThat(plan.items()).extracting(SubIntent::capabilityId)
                .containsExactly("cap.transfer", "cap.balance");
        assertThat(plan.items().get(1).condition()).isEqualTo("不足就别转");
        assertThat(plan.source()).isEqualTo(IntentPlan.Source.PLANNER);
    }

    @Test
    @DisplayName("模型只给一步时保留规则拆解：漏识别多意图会真的只办其中一件")
    void singleStepKeepsTheRulePlan() {
        Optional<IntentPlan> plan = fold(
                List.of(new TaskProposal(TRANSFER, Map.of(), "转账")), rulePlanWithCondition());

        assertThat(plan.orElseThrow().source()).isEqualTo(IntentPlan.Source.RULE);
        assertThat(plan.orElseThrow().items()).hasSize(2);
    }

    @Test
    @DisplayName("规划抛错回退规则拆解，不把用户从「说得出两件事」打回「什么也说不出」")
    void plannerFailureFallsBack() {
        IntentPlanner broken = new ReActIntentPlanner(new SlowPathProperties()) {
            @Override
            protected List<TaskProposal> propose(String goal, List<CapabilityCard> candidates) {
                throw new IllegalStateException("网关挂了");
            }
        };

        Optional<IntentPlan> plan = broken.plan("查余额再转账", List.of(BALANCE), rulePlanWithCondition());

        assertThat(plan.orElseThrow().source()).isEqualTo(IntentPlan.Source.RULE);
    }

    @Test
    @DisplayName("没有候选能力就不调模型，直接回退")
    void noCandidatesSkipsTheModel() {
        IntentPlanner planner = new ReActIntentPlanner(new SlowPathProperties()) {
            @Override
            protected List<TaskProposal> propose(String goal, List<CapabilityCard> candidates) {
                throw new AssertionError("没有候选还去调模型，等于白花一次往返");
            }
        };

        IntentPlan fallback = rulePlanWithCondition();
        assertThat(planner.plan("随便问问", List.of(), fallback)).containsSame(fallback);
    }

    @Test
    @DisplayName("Planner 不做候选越界判断，但不得丢失规则锚定的条件")
    void outOfCandidateProposalStillKeepsAnchoredCondition() {
        CapabilityCard otherTransfer = card("cap.transfer.other", "别的转账", RiskLevel.R2);

        IntentPlan plan = fold(List.of(
                new TaskProposal(BALANCE, Map.of(), "查余额"),
                new TaskProposal(otherTransfer, Map.of(), "转账")), rulePlanWithCondition()).orElseThrow();

        assertThat(plan.items().get(1).capabilityId()).isEqualTo("cap.transfer.other");
        assertThat(plan.items().get(1).condition()).isEqualTo("不足就别转");
    }

    private static CapabilityCard card(String id, String name, RiskLevel risk) {
        return new CapabilityCard(id, name, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                null, List.of("bank"), name, List.of(), Map.of(), Map.of(), List.of(), List.of(),
                risk, 3000, Enums.Idempotency.REQUIRED, "owner", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(), null);
    }
}
