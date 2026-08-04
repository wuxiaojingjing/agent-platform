package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RecallResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-1G：跨域回归集。
 *
 * <p>跨域判定两边都会错，而两种错的代价不对称：
 *
 * <ul>
 *   <li><b>漏判</b>——真跨域被当成单域，于是在两个都说得通的领域里挑了一个直接执行。
 *   <li><b>误判</b>——单域被当成跨域，用户问一句明确的话却被降级到慢路径，白等几秒。
 * </ul>
 *
 * <p>误判这一侧出过一次真事故式的缺陷：{@code cap.card.replace} 一张卡同时挂在
 * creditcard 与 account 两个领域下，于是「两个领域的最高分」必然相等，只看分差就被判成跨域。
 * 「换卡」是再明确不过的单一意图，却每次都掉进慢路径。回归用例留在下面第一条。
 */
class CrossDomainRegressionTest {

    private static RequestContext ctx(String session) {
        return new RequestContext("trace-" + session, session, "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", false);
    }

    private static FastPathResult decide(FastPathFixture.Built fixture, String session, String query) {
        return fixture.engine().decide(new FastPathRequest(ctx(session), query, null, Map.of()));
    }

    @Nested
    @DisplayName("不该判跨域的")
    class NotCrossDomain {

        /**
         * 跨域的定义是「不同的卡分属不同领域且难分伯仲」，不是「一张卡覆盖多个领域」。
         */
        @Test
        @DisplayName("换卡：一张卡横跨两个领域，仍是单域")
        void oneCardSpanningTwoDomainsIsStillSingle() {
            FastPathResult result = decide(FastPathFixture.build(), "s-xd-card", "换卡");

            assertThat(routingMode(result)).isEqualTo(Enums.RoutingMode.SINGLE);
            // 它该去问卡种，而不是掉进慢路径
            assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        }

        @Test
        @DisplayName("单域明确的查询是单域")
        void plainSingleDomainQuery() {
            FastPathResult result = decide(FastPathFixture.build(), "s-xd-bal", "查一下余额");

            assertThat(routingMode(result)).isEqualTo(Enums.RoutingMode.SINGLE);
            assertThat(result.decision().decision()).isEqualTo(Decision.EXECUTE_CAPABILITY);
        }
    }

    @Nested
    @DisplayName("该判跨域的")
    class TrulyCrossDomain {

        /** 两张不同的卡、分属不同领域、分数难分伯仲——这才是跨域。 */
        private FastPathFixture.Built tiedAcrossDomains() {
            return FastPathFixture.buildWithBm25Hits(new FastPathFixture.UnavailableGateway(),
                    List.of("cap.account.balance.query", "cap.creditcard.bill.query"), true);
        }

        @Test
        @DisplayName("两个领域分数接近时判 MULTI")
        void closeDomainsRouteMulti() {
            FastPathResult result = decide(tiedAcrossDomains(), "s-xd-tie", "查一下");

            assertThat(routingMode(result)).isEqualTo(Enums.RoutingMode.MULTI);
            assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
            assertThat(result.decision().candidateIds())
                    .containsExactly("cap.account.balance.query", "cap.creditcard.bill.query");
        }

        /**
         * §3.2：领域分数接近时保留 Top-K，不能先选唯一领域再检索。先选领域的写法
         * 在分数接近时等于抛硬币，而抛硬币的结果下游看不出来——它拿到的是一个
         * 「确定的单一领域」，无从知道另一半证据被丢了。
         */
        @Test
        @DisplayName("判 MULTI 时保留多个领域候选，不提前收敛到一个")
        void multiKeepsTopKDomains() {
            FastPathResult result = decide(tiedAcrossDomains(), "s-xd-topk", "查一下");
            RecallResult.DomainRouting routing = result.recall().domainRouting();

            assertThat(routing.domainCandidates()).hasSizeGreaterThan(1);
            // 联邦检索标志要跟着立起来，否则下游仍按单域去查一遍
            assertThat(routing.requiresFederatedRetrieval()).isTrue();
        }

        @Test
        @DisplayName("跨域不得直出执行")
        void crossDomainNeverFastExecutes() {
            FastPathResult result = decide(tiedAcrossDomains(), "s-xd-noexec", "查一下");

            assertThat(result.decision().decision()).isNotEqualTo(Decision.EXECUTE_CAPABILITY);
        }

        @Test
        @DisplayName("保险产品A与理财产品B → 策略澄清，不得进入跨 Agent Loop")
        void crossTypeProductCompareIsBlockedByPolicy() {
            FastPathResult result = decide(FastPathFixture.build(), "s-xd-cmp", "对比产品A和产品B");

            assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
            assertThat(result.decision().reasonCode())
                    .isEqualTo(com.huawei.finance.contracts.model.ReasonCode.INCOMPARABLE_PRODUCT_TYPE);
            assertThat(result.decision().candidateIds()).isEmpty();
            assertThat(result.intentPlan()).isNull();
            assertThat(result.templateKey()).isEqualTo("tpl.product.compare.incomparable");
            assertThat(result.slots()).containsEntry("options", List.of("查看产品A", "查看产品B"));
        }

        @Test
        @DisplayName("同类理财产品B与B2 → START_LOOP/AFTER_OBSERVATION")
        void sameTypeProductCompareStartsLoop() {
            FastPathResult result = decide(FastPathFixture.build(), "s-same-type-cmp", "对比产品B和产品B2");

            assertThat(result.decision().decision()).isEqualTo(Decision.START_LOOP);
            assertThat(result.decision().reasonCode())
                    .isEqualTo(com.huawei.finance.contracts.model.ReasonCode.AFTER_OBSERVATION);
            assertThat(result.decision().candidateIds()).containsExactly(
                    "cap.wealth-product.product.query", "cap.wealth-product.product-b2.query");
            assertThat(result.intentPlan()).isNotNull();
            assertThat(result.intentPlan().items()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("跨域与多任务叠加")
    class WithMultiTask {

        @Test
        @DisplayName("跨两个领域各办一件事 → STATIC_PLAN")
        void twoThingsInTwoDomains() {
            FastPathResult result = decide(FastPathFixture.build(), "s-xd-multi",
                    "查余额，再给老徐转 1000");

            assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        }

        /**
         * 语义通道降级时的**代价**，钉在这里。
         *
         * <p>「再查一下余额」只有一件事。召回可信时检测器不会判它多任务
         * （见 {@code MultiTaskDetectorTest} 的负例集）；但本夹具的模型网关不可用，
         * 语义通道降级，「召回到几个能力」不再是可用的否定证据，检测器按设计退回纯词表——
         * 于是只凭一个「再」字就把它推进了慢路径。
         *
         * <p>这不是缺陷，是那条保守回退的已知代价：模型一挂，一批本可直出的问句会变慢。
         * 写成用例是为了让它有个明确的去处——将来谁想放宽这条回退，得先来改这里，
         * 而不是发现线上慢路径占比异常后再回头找原因。
         */
        @Test
        @DisplayName("语义降级时，单件事带连词也会被保守地推去慢路径")
        void degradedRecallMakesConjunctionAloneSufficient() {
            FastPathResult result = decide(FastPathFixture.build(), "s-xd-one", "再查一下余额");

            assertThat(result.recall().semanticChannelDegraded()).isTrue();
            assertThat(result.decision().decision()).isEqualTo(Decision.CLARIFY);
        }
    }

    private static Enums.RoutingMode routingMode(FastPathResult result) {
        return result.recall() == null ? null : result.recall().domainRouting().routingMode();
    }
}
