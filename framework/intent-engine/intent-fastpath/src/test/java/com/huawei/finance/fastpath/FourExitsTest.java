package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新入口路由的真实场景，覆盖不同执行去向。
 *
 * <p>模型网关在这些用例里一律不可用，走的是规则仲裁回退。这不是为了图省事：
 * 出口分流在没有模型时也必须成立，否则「模型挂了」就等于「系统挂了」。
 */
class RouteDecisionTest {

    private static final FastPathFixture.Built FIXTURE = FastPathFixture.build();

    private static RequestContext ctx(String sessionId) {
        return new RequestContext("trace-" + sessionId, sessionId, "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", false);
    }

    private FastPathResult decide(String sessionId, String query) {
        return FIXTURE.engine().decide(new FastPathRequest(ctx(sessionId), query, null, Map.of()));
    }

    @Test
    @DisplayName("查一下余额 → EXECUTE_CAPABILITY")
    void balanceQueryFastExecutes() {
        FastPathResult result = decide("s-balance", "查一下余额");

        assertThat(result.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.HIGH_CONFIDENCE);
        assertThat(result.decision().selectedCandidateId()).isEqualTo("cap.account.balance.query");
        assertThat(result.decision().missingSlots()).isEmpty();
    }

    @Test
    @DisplayName("换卡 → CLARIFY，且只问卡种一个问题")
    void cardReplaceClarifies() {
        FastPathResult result = decide("s-card", "换卡");

        assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.MISSING_SLOT);
        assertThat(result.decision().selectedCandidateId()).isEqualTo("cap.card.replace");
        // v0.7 §3.4：每次只问一个必要问题
        assertThat(result.decision().missingSlots()).containsExactly("cardType");
    }

    @Test
    @DisplayName("能力与条件均已确定的多任务进入静态计划")
    void multiTaskGoesSlowPath() {
        FastPathResult result = decide("s-multi", "查余额，再给老徐转 1000；不足就别转");

        assertThat(result.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);
        // 条件依赖是判定依据，evidence 要能说明是哪一类信号触发的
        assertThat(result.decision().evidenceRefs()).contains("multitask:conditional");
        assertThat(result.intentPlan()).isNotNull();
        assertThat(result.intentPlan().fullyResolved()).isTrue();
    }

    @Test
    @DisplayName("工资未到账的固定结果规则生成完整条件计划")
    void payrollConditionProducesResolvedPlan() {
        FastPathResult result = decide("s-payroll-condition", "工资没到账，就检查工资卡状态");

        assertThat(result.intentPlan()).isNotNull();
        assertThat(result.intentPlan().items()).extracting(com.huawei.finance.contracts.model.SubIntent::capabilityId)
                .containsExactly("cap.payroll.arrival.query", "cap.account.card.status.query");
        assertThat(result.intentPlan().hasConditional()).isTrue();
        assertThat(result.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.RESULT_RULE);
    }

    @Test
    @DisplayName("完整固定计划不因召回显著候选计数不足退回灰区")
    void resolvedFixedPlanOverridesWeakAggregateRecall() {
        FastPathResult result = decide("s-fixed-resolved", "查一下余额，然后查询基金产品C");

        assertThat(result.decision().decision()).isEqualTo(Decision.STATIC_PLAN);
        assertThat(result.intentPlan()).isNotNull();
        assertThat(result.intentPlan().fullyResolved()).isTrue();
        assertThat(result.intentPlan().items())
                .extracting(com.huawei.finance.contracts.model.SubIntent::capabilityId)
                .containsExactly("cap.account.balance.query", "cap.fund.product.query");
    }

    @Test
    @DisplayName("未开放的信用卡额度调整 → HANDOFF，走二级短路不调仲裁")
    void creditLimitAdjustRejected() {
        FastPathResult result = decide("s-limit", "帮我把信用卡额度改成 10 万");

        assertThat(result.decision().decision()).isEqualTo(Decision.HANDOFF);
        // 未开放能力是 POLICY_BLOCK 而不是 NO_CANDIDATE：听懂了但不能办，与没听懂是两回事
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.POLICY_BLOCK);
        assertThat(result.decision().shortCircuit()).isEqualTo(ShortCircuitLevel.L2_STRONG_RULE);
        assertThat(result.templateKey()).isEqualTo("tpl.reject.capability-not-open");
        assertThat(result.slots()).containsEntry("capabilityName", "信用卡额度调整");
    }

    @Test
    @DisplayName("转账缺收款人与金额 → CLARIFY，不得直出执行")
    void transferWithoutSlotsClarifies() {
        FastPathResult result = decide("s-transfer", "我要转账");

        assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        assertThat(result.decision().missingSlots()).isNotEmpty();
    }

    /**
     * 用能力卡上的示例问法发起：本夹具关掉了 BM25 与语义通道，规则通道对说法变体的覆盖有限，
     * 换个说法未必召得回。说法泛化能力要靠接上 OpenSearch 的集成测试来验，
     * 这里验的是「槽位齐全的 R2 走哪个出口」。
     */
    @Test
    @DisplayName("转账信息齐全 → EXECUTE_CAPABILITY 但标注需显式确认")
    void transferWithSlotsRequiresConfirmation() {
        FastPathResult result = decide("s-transfer-full", "给张三转 1000");

        assertThat(result.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        // R2 返回 EXECUTE_CAPABILITY，但用 CONFIRMATION_REQUIRED 要求中控先确认。
        assertThat(result.decision().reasonCode()).isEqualTo(ReasonCode.CONFIRMATION_REQUIRED);
        assertThat(result.slots()).containsEntry("payee", "张三").containsEntry("amount", "1000");
    }
}
