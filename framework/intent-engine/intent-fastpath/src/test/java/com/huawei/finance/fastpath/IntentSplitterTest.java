package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.fastpath.recall.IntentSplitter;
import com.huawei.finance.fastpath.recall.RuleRecall;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 多意图切分。
 *
 * <p>这组用例盯着两件事：拆出来的**次序**和**关系**。次序错了会错序执行（先转账后查余额），
 * 关系错了会丢掉条件（「不足就别转」变成无条件转账）。两者都不会在只看出口的评测里暴露——
 * 出口照样是 STATIC_PLAN。
 */
class IntentSplitterTest {

    private static final AssetBundle BUNDLE = FastPathFixture.assets();
    private static final IntentSplitter SPLITTER =
            new IntentSplitter(BUNDLE, new RuleRecall(BUNDLE));

    @Test
    @DisplayName("场景 3：查余额、再转账、不足就别转 → 两件事，转账条件依赖于查询")
    void conditionalTransferKeepsItsCondition() {
        IntentPlan plan = SPLITTER.split("查余额，再给老徐转 1000；不足就别转").orElseThrow();

        assertThat(plan.items()).hasSize(2);

        SubIntent query = plan.items().get(0);
        assertThat(query.text()).contains("查余额");
        assertThat(query.relation())
                .as("第一件事没有前序可等")
                .isEqualTo(Enums.IntentRelation.PARALLEL);

        SubIntent transfer = plan.items().get(1);
        assertThat(transfer.text()).contains("转");
        assertThat(transfer.capabilityId()).isEqualTo("cap.transfer");
        assertThat(transfer.resolution().strength()).isEqualTo(PlanResolution.Strength.LOCKED);
        assertThat(transfer.relation())
                .as("「不足就别转」意味着转不转要看查询结果，这正是并列与依赖的分界")
                .isEqualTo(Enums.IntentRelation.CONDITIONAL);
        assertThat(transfer.condition())
                .as("条件原文要留住，下游才判得了条件成立与否")
                .contains("不足");
        assertThat(plan.hasConditional()).isTrue();
        assertThat(plan.fullyResolved()).isTrue();
    }

    @Test
    @DisplayName("纯条件从句不算一件事：它是对邻近那件事的限定")
    void conditionClauseIsNotACountedTask() {
        IntentPlan plan = SPLITTER.split("查余额，再给老徐转 1000；不足就别转").orElseThrow();

        assertThat(plan.items())
                .as("三个分句只对应两件待办，多出来的那句是条件")
                .hasSize(2);
        assertThat(plan.summaries())
                .noneMatch(summary -> summary.contains("别转"));
    }

    @Test
    @DisplayName("条件从句里只以否定式露过头的那件事要补回来：「先查余额，不足就别转」是两件事")
    void elidedTaskInConditionClauseIsRecovered() {
        IntentPlan plan = SPLITTER.split("先查余额，不足就别转")
                .orElseThrow(() -> new AssertionError(
                        "拆不出计划的话，用户收到的是一句列不出选项的「先办哪一件」，没法选也开不了计划"));

        assertThat(plan.items()).hasSize(2);
        assertThat(plan.items().get(0).text()).contains("余额");

        SubIntent transfer = plan.items().get(1);
        assertThat(transfer.capabilityId())
                .as("转账全程只在「别转」里露过一个字，但用户确实是要转")
                .isEqualTo("cap.transfer");
        assertThat(transfer.relation()).isEqualTo(Enums.IntentRelation.CONDITIONAL);
        assertThat(transfer.condition())
                .as("条件原文留住，下游才判得了余额够不够")
                .contains("不足");
    }

    @Test
    @DisplayName("那件事若已被正面说过，条件从句仍旧并进去，不许冒出第三件")
    void alreadyStatedTaskIsNotDuplicated() {
        IntentPlan plan = SPLITTER.split("查余额，再给老徐转 1000；不足就别转").orElseThrow();

        assertThat(plan.items()).hasSize(2);
        assertThat(plan.items()).filteredOn(i -> i.text().contains("转"))
                .as("同一笔转账被拆成两条，用户就会被转两次钱")
                .hasSize(1);
        assertThat(plan.items().get(1).condition())
                .as("「不足就别转」并回它所限定的那笔转账，而不是另起一件")
                .contains("不足");
    }

    @Test
    @DisplayName("动作先说但明确要求先查询时，按依赖顺序执行而不是按语序转账")
    void explicitFirstQueryReordersConditionalTransfer() {
        IntentPlan plan = SPLITTER.split("帮我转两千给张三，先查余额，不足就别转。").orElseThrow();

        assertThat(plan.items()).extracting(SubIntent::capabilityId)
                .containsExactly("cap.account.balance.query", "cap.transfer");
        assertThat(plan.items().get(0).relation()).isEqualTo(Enums.IntentRelation.PARALLEL);
        assertThat(plan.items().get(1).relation()).isEqualTo(Enums.IntentRelation.CONDITIONAL);
        assertThat(plan.items().get(1).condition()).contains("不足");
    }

    @Test
    @DisplayName("否定词后面的字对不上唯一一张卡就不猜，退回「一件事加一个条件」")
    void ambiguousNegationIsNotGuessed() {
        assertThat(SPLITTER.split("先查余额，不够就别办"))
                .as("「办」不是任何关键词的首字。猜不出是哪件事，就只剩查余额一件，不出计划")
                .isEmpty();
    }

    @Test
    @DisplayName("顺序连词判依赖，并列连词判并行——两者下游执行策略不同")
    void sequentialAndParallelAreToldApart() {
        IntentPlan sequential = SPLITTER.split("先查一下余额，然后看看信用卡账单").orElseThrow();
        assertThat(sequential.items().get(1).relation())
                .isEqualTo(Enums.IntentRelation.SEQUENTIAL);

        IntentPlan parallel = SPLITTER.split("查一下余额，顺便看看信用卡账单").orElseThrow();
        assertThat(parallel.items().get(1).relation())
                .isEqualTo(Enums.IntentRelation.PARALLEL);
    }

    @Test
    @DisplayName("次序与列表位置必须一致：错位就是错序执行")
    void orderMatchesPosition() {
        IntentPlan plan = SPLITTER.split("查一下余额，然后看看信用卡账单").orElseThrow();

        for (int i = 0; i < plan.items().size(); i++) {
            assertThat(plan.items().get(i).order()).isEqualTo(i);
        }
        assertThat(plan.first().text()).contains("余额");
    }

    @Test
    @DisplayName("认出能力的用卡名做话术，认不出的退回用户原话")
    void summaryPrefersCapabilityName() {
        IntentPlan plan = SPLITTER.split("查一下余额，然后帮我看看今天天气").orElseThrow();

        assertThat(plan.items().get(0).resolved()).isTrue();
        assertThat(plan.items().get(0).summary())
                .as("用户该看到「余额查询」而不是自己刚说过的半句话")
                .doesNotContain("查一下");

        SubIntent weather = plan.items().get(1);
        assertThat(weather.resolved()).as("天气不在能力清单里").isFalse();
        assertThat(weather.summary()).contains("天气");
        assertThat(plan.fullyResolved())
                .as("有一件认不出就不能整条自动执行")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "如果我卡丢了怎么办",
            "要是余额不够会怎样",
            "查一下余额",
            "再查一下余额",
            "查一下余额和信用卡账单",
    })
    @DisplayName("切不出两件事就不给计划，调用方退回原有话术")
    void refusesToInventTasks(String query) {
        Optional<IntentPlan> plan = SPLITTER.split(query);

        assertThat(plan)
                .as("「%s」切不出两件独立的事。给一份长度为 1 的计划会让下游以为这是多意图", query)
                .isEmpty();
    }

    @Test
    @DisplayName("空输入不炸：多任务信号可能来自词表而查询已被改写成空串")
    void emptyInputIsSafe() {
        assertThat(SPLITTER.split(null)).isEmpty();
        assertThat(SPLITTER.split("   ")).isEmpty();
        assertThat(SPLITTER.split("，，，")).isEmpty();
    }

    @Test
    @DisplayName("标点切碎的尾巴并回上一段，不冒充一件事")
    void tinyFragmentsAreMergedBack() {
        IntentPlan plan = SPLITTER.split("查一下余额，，然后看看信用卡账单").orElseThrow();

        assertThat(plan.items()).hasSize(2);
        assertThat(plan.items()).allSatisfy(item ->
                assertThat(item.text().length()).isGreaterThanOrEqualTo(2));
    }

    @Test
    @DisplayName("验收表达的余额与基金步骤都由强规则锁定")
    void acceptancePlanLocksBalanceAndFund() {
        IntentPlan plan = SPLITTER.split("查一下余额，然后查询基金产品C").orElseThrow();

        assertThat(plan.items()).extracting(SubIntent::capabilityId)
                .containsExactly("cap.account.balance.query", "cap.fund.product.query");
        assertThat(plan.items()).extracting(item -> item.resolution().strength())
                .containsOnly(PlanResolution.Strength.LOCKED);
        assertThat(plan.items()).allSatisfy(item -> {
            assertThat(item.resolution().topScore()).isGreaterThanOrEqualTo(0.55);
            assertThat(item.resolution().candidateIds()).contains(item.capabilityId());
            assertThat(item.resolution().evidenceRefs()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("弱证据保留候选但不锁死，零证据保持未识别")
    void weakAndUnknownClausesAreClassified() {
        IntentPlan weak = SPLITTER.split("查询账户，然后查询基金").orElseThrow();
        assertThat(weak.items()).allSatisfy(item ->
                assertThat(item.resolution().strength()).isEqualTo(PlanResolution.Strength.PREFERRED));

        IntentPlan unknown = SPLITTER.split("查一下余额，然后帮我看看今天天气").orElseThrow();
        SubIntent weather = unknown.items().get(1);
        assertThat(weather.resolution().strength()).isEqualTo(PlanResolution.Strength.UNRESOLVED);
        assertThat(weather.resolution().candidateIds()).isEmpty();
    }

    @Test
    @DisplayName("没有导航动词时排除导航卡，明确打开页面时才允许导航卡")
    void navigationCandidatesRequireNavigationMarker() {
        IntentPlan query = SPLITTER.split("查一下余额，然后查询基金产品C").orElseThrow();
        assertThat(query.items().get(1).resolution().candidateIds())
                .contains("cap.fund.product.query")
                .noneMatch(id -> id.startsWith("cap.nav."));

        IntentPlan navigation = SPLITTER.split("查一下余额，然后打开基金选品").orElseThrow();
        assertThat(navigation.items().get(1).resolution().candidateIds())
                .contains("cap.nav.fund_service_基金选品");
    }
}
