package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.fastpath.recall.NegativeFilter;
import com.huawei.finance.fastpath.rule.ExpressionEvaluator;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-17：负向过滤的**效果**。
 *
 * <p>此前只验到「规则文件能加载、条数对得上」——那验的是 YAML 解析器，不是过滤器。
 * 一条 {@code suppress} 写错能力 ID、或 {@code penalty} 被人从 0.6 调成 0.06，
 * 加载依旧成功，条数依旧对得上。
 *
 * <p>负向分不是调味料。它挡的是「把『查还款日』做成『还款』」这一类——
 * 近邻能力里恰好有一个带副作用的，选错就是真扣钱。所以这里要验三件事：
 * 打压真的落到了分上、原因留了痕、以及**没有误伤该选的那个**。
 */
class NegativeFilterEffectTest {

    private static AssetBundle bundle;
    private static NegativeFilter filter;

    @BeforeAll
    static void setUp() {
        bundle = FastPathFixture.assets();
        filter = NegativeFilter.of(bundle, new ExpressionEvaluator());
    }

    private static NegativeFilter.Result apply(String query) {
        return filter.apply(Map.of("query", query));
    }

    @Nested
    @DisplayName("打压落到分上")
    class Penalties {

        @Test
        @DisplayName("查询类动词压制带副作用的能力")
        void queryVerbSuppressesSideEffects() {
            NegativeFilter.Result r = apply("查一下还款日");

            assertThat(r.penaltyOf("cap.creditcard.repay")).isGreaterThan(0);
            assertThat(r.penaltyOf("cap.transfer")).isGreaterThan(0);
        }

        @Test
        @DisplayName("问理财持仓时压制资金划转")
        void wealthSuppressesFundMovement() {
            NegativeFilter.Result r = apply("我的基金持仓收益怎么样");

            assertThat(r.penaltyOf("cap.transfer")).isGreaterThan(0);
            assertThat(r.penaltyOf("cap.creditcard.repay")).isGreaterThan(0);
        }

        /**
         * 累加而非取最大。两条规则各自独立地说「不该选它」，证据是叠加的；
         * 取最大等于把其中一条的判断丢掉。
         */
        @Test
        @DisplayName("多条规则同时命中时打压分累加")
        void penaltiesAccumulate() {
            // 「查」触发查询动词规则，「理财」触发理财规则，两条都压 cap.transfer
            double both = apply("查一下我的理财").penaltyOf("cap.transfer");
            double queryOnly = apply("查一下").penaltyOf("cap.transfer");
            double wealthOnly = apply("我的理财").penaltyOf("cap.transfer");

            assertThat(queryOnly).isGreaterThan(0);
            assertThat(wealthOnly).isGreaterThan(0);
            assertThat(both).isEqualTo(queryOnly + wealthOnly);
        }
    }

    @Nested
    @DisplayName("守卫条件真的守得住")
    class Guards {

        /**
         * {@code neg.balance-not-bill} 的条件里带两个否定项。否定项写错方向，
         * 规则会在「查信用卡账单」这种它本该让路的场景里把正确答案压下去——
         * 而这种错误在「规则能加载」的检查里是完全看不见的。
         */
        @Test
        @DisplayName("用户明说信用卡账单时，压制账单的那条规则让路")
        void billRuleStepsAsideWhenUserSaysBill() {
            assertThat(apply("查一下信用卡账单").penaltyOf("cap.creditcard.bill.query"))
                    .isZero();
        }

        @Test
        @DisplayName("只说余额时才压制账单候选")
        void billIsSuppressedForPlainBalance() {
            assertThat(apply("我的余额还有多少").penaltyOf("cap.creditcard.bill.query"))
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("无关查询一条也不命中")
        void unrelatedQueryTriggersNothing() {
            assertThat(apply("你好").penalties()).isEmpty();
        }
    }

    @Nested
    @DisplayName("留痕")
    class Attribution {

        /** 线上出现「该召回的被压没了」时，没有原因就只能靠猜是哪条规则干的。 */
        @Test
        @DisplayName("打压原因带 ruleId")
        void reasonsCarryRuleId() {
            assertThat(apply("查一下还款日").reasonsOf("cap.creditcard.repay"))
                    .anyMatch(r -> r.contains("neg.query-verb-suppresses-transaction"));
        }

        @Test
        @DisplayName("原因随打压一起进入候选的 matchedEvidence")
        void evidenceReachesTheCandidate() {
            Optional<RecallResult.Candidate> transfer = candidate("查一下余额", "cap.transfer");

            assertThat(transfer).isPresent();
            assertThat(transfer.get().matchedEvidence())
                    .anyMatch(e -> e.startsWith("negative:"));
        }
    }

    @Nested
    @DisplayName("压对了人，也没误伤")
    class NoCollateralDamage {

        /**
         * 这是 FP-17 真正要守的判定：被压的候选排在正确答案之后。
         *
         * <p>用 {@code buildWithBm25Hits} 强行把 {@code cap.transfer} 塞进候选集——
         * 默认装配下「查一下余额」根本召不回转账，那样断言「转账被压下去了」是空跑的。
         */
        @Test
        @DisplayName("查余额时转账候选的负向分不为零，且排在余额之后")
        void suppressedCandidateRanksBelow() {
            List<RecallResult.Candidate> candidates = candidates("查一下余额");

            int balanceAt = indexOf(candidates, "cap.account.balance.query");
            int transferAt = indexOf(candidates, "cap.transfer");

            assertThat(balanceAt).isGreaterThanOrEqualTo(0);
            assertThat(transferAt).isGreaterThan(balanceAt);
        }

        @Test
        @DisplayName("被压的是转账，余额本身一分未减")
        void theRightAnswerIsUntouched() {
            Optional<RecallResult.Candidate> balance =
                    candidate("查一下余额", "cap.account.balance.query");
            Optional<RecallResult.Candidate> transfer = candidate("查一下余额", "cap.transfer");

            assertThat(balance).isPresent();
            assertThat(balance.get().scores().negative()).isZero();
            assertThat(transfer.orElseThrow().scores().negative()).isGreaterThan(0);
        }
    }

    /** 走一遍真实召回，强行让候选集包含转账与账单，才看得出打压的排序效果。 */
    private static List<RecallResult.Candidate> candidates(String query) {
        FastPathResult result = FastPathFixture
                .buildWithBm25Hits(new FastPathFixture.UnavailableGateway(),
                        List.of("cap.account.balance.query", "cap.transfer", "cap.creditcard.bill.query"))
                .engine()
                .decide(new FastPathRequest(
                        new RequestContext("trace-neg", "s-neg", "u-1", "MOBILE_BANK", "home", "", false),
                        query, null, Map.of()));
        return result.recall() == null ? List.of() : result.recall().candidates();
    }

    private static Optional<RecallResult.Candidate> candidate(String query, String capabilityId) {
        return candidates(query).stream()
                .filter(c -> capabilityId.equals(c.candidateId()))
                .findFirst();
    }

    private static int indexOf(List<RecallResult.Candidate> candidates, String capabilityId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (capabilityId.equals(candidates.get(i).candidateId())) {
                return i;
            }
        }
        return -1;
    }
}
