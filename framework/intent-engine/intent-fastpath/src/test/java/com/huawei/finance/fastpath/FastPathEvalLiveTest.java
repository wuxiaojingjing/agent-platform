package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.fastpath.eval.EvalCase;
import com.huawei.finance.fastpath.eval.EvalSet;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.RegistryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * 接真检索的快路径评测（FP-52 的第二档）。
 *
 * <p>{@link FastPathEvalTest} 跑的是降级态：检索通道整体停用，只剩规则召回。那一档负责
 * **回归锁**——完全确定、可无限重跑。这一档回答的是另一个问题：**检索到底救不救得回来**。
 *
 * <p>它存在的直接原因是种子集第一次跑出来的那两条：「帮我转 500 给老徐」与
 * 「给张三转 1,000 元」在降级态下被判无候选。当时把它们记成了「只在降级态暴露」，
 * 但那句话在没有真索引跑过之前只是推测。推测写进文档就会被当成结论，所以要有这一档。
 *
 * <p>这里**不断言等于种子集的期望**——那些期望记的是降级态现状，接上检索后本就该不同，
 * 强行对齐等于要求检索什么也不改变。这里只断言两类东西：
 *
 * <ul>
 *   <li><b>任何配置下都必须成立的不变量</b>：R2 仍须确认、强规则仍在 L2 短路、
 *       越界问句不得变成可执行。检索变强绝不能换来这些的松动。</li>
 *   <li><b>降级差异</b>：把两档的差别打成报告，供人判断资产该怎么补。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FastPathEvalLiveTest {

    private static OpenSearchClient client;
    private static IndexReadiness readiness;
    private static CandidateSearch search;
    private static ModelGatewayClient queryGateway;
    private static boolean vectorsIndexed;

    @BeforeAll
    static void buildLiveIndex() throws Exception {
        client = FastPathLiveFixture.connect();
        if (client == null) {
            return;
        }
        AssetBundle bundle = FastPathLiveFixture.assets();
        RegistryProperties props = FastPathLiveFixture.properties();

        // 建索引与查询用同一个网关：无密钥时两侧都没有向量能力，
        // 若只在一侧回落，就会出现「索引里有向量、查询算不出向量」这种线上不存在的组合
        queryGateway = FastPathLiveFixture.realGateway();
        readiness = FastPathLiveFixture.buildIndex(client, props, bundle, queryGateway);
        search = FastPathLiveFixture.candidateSearch(client, props, readiness);
        vectorsIndexed = readiness.get().semanticAvailable();

        System.out.printf("%n===== 评测第二档：真检索 =====%nOpenSearch 已连；向量通道 %s%n",
                vectorsIndexed ? "已开（含 API key）" : "未开（无 SILICONFLOW_API_KEY，仅 BM25 字面通道）");
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            FastPathLiveFixture.dropIndices(client);
        }
    }

    @BeforeEach
    void requireOpenSearch() {
        assumeTrue(client != null, "OpenSearch 未就绪，跳过真检索评测");
        assumeTrue(readiness.get().searchable(), "索引未建成，跳过真检索评测");
    }

    private FastPathEvalRunner.Actual run(EvalCase testCase) {
        return FastPathEvalRunner.run(
                FastPathLiveFixture.build(search, queryGateway), testCase);
    }

    private EvalCase caseById(String id) {
        return EvalSet.load().getCases().stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("种子集里没有用例 " + id));
    }

    @Test
    @DisplayName("两档对照报告：接上检索之后哪些变了")
    void reportsDifferenceAgainstDegradedBaseline() {
        List<String> changed = new ArrayList<>();
        List<String> gapsClosed = new ArrayList<>();
        int same = 0;

        for (EvalCase c : EvalSet.load().getCases()) {
            FastPathEvalRunner.Actual actual = run(c);
            List<String> diffs = FastPathEvalRunner.diff(c, actual);
            if (diffs.isEmpty()) {
                same++;
                continue;
            }
            // 带上完整实测而不只是差异：差异只说「哪个字段不同」，
            // 而判断资产该怎么补需要知道它到底召回到了哪张卡
            String line = "  " + c.getId() + "（" + c.getStatus() + "）" + diffs
                    + "\n      实测 " + FastPathEvalRunner.asYamlExpect(actual);
            if (c.knownGap()) {
                gapsClosed.add(line);
            } else {
                changed.add(line);
            }
        }

        System.out.println("与降级态一致 " + same + " 条");
        System.out.println("已知缺口发生变化 " + gapsClosed.size() + " 条：");
        gapsClosed.forEach(System.out::println);
        System.out.println("现状锁发生变化 " + changed.size() + " 条：");
        changed.forEach(System.out::println);

        // 不断言数量。这份报告是给人读的判断材料，钉一个数字只会让人在资产合理演进时来改这个数字
        assertThat(same + gapsClosed.size() + changed.size())
                .isEqualTo(EvalSet.load().getCases().size());
    }

    /**
     * 全通道下的总闸：**任何**被选中的 R2 能力都不得无确认直出。
     *
     * <p>不是只查转账那一条，而是扫全集。理由是第二档实测撞上的这件事：
     * 「这个月要还多少」两次跑分别选中了 `cap.creditcard.bill.query`（只读）与
     * `cap.creditcard.repay`（有资金动作）。选错能力已经够糟，但真正的底线是
     * **选错也不能直接执行**。这条断言守的是底线，而不是选得准不准。
     */
    @Test
    @DisplayName("全集扫一遍：选中 R2 能力时必须有确认或澄清")
    void noR2ExecutesWithoutConfirmation() {
        AssetBundle bundle = FastPathLiveFixture.assets();
        for (EvalCase c : EvalSet.load().getCases()) {
            FastPathEvalRunner.Actual actual = run(c);
            if (actual.capability() == null) {
                continue;
            }
            var card = bundle.capability(actual.capability());
            if (card == null || card.riskLevel() != RiskLevel.R2) {
                continue;
            }
            assertThat("EXECUTE_CAPABILITY".equals(actual.decision())
                    && !"CONFIRMATION_REQUIRED".equals(actual.reasonCode()))
                    .as("用例 %s 选中了 R2 能力 %s 却以 %s 直出，未经确认",
                            c.getId(), actual.capability(), actual.reasonCode())
                    .isFalse();
        }
    }

    /**
     * 抖动探测：同一句话连跑三次，选中的能力是否稳定。
     *
     * <p>取「这个月要还多少」是因为它实测就是不稳的那条，而且它的两个候选一个只读、
     * 一个动钱。仲裁走 {@code temperature=0}，所以这里的不稳定不是采样随机性，
     * 更可能是候选顺序或分数在边界上抖——那种抖只在真检索下才存在，
     * 第一档（规则单通道）永远看不到。
     *
     * <p>断言只到「不得跨到无确认执行」为止。要求它每次都选同一张卡是应该的，
     * 但那是效果门槛，得业务与算法定；这里先把安全底线钉住，把不稳定打印出来给人看。
     */
    @Test
    @DisplayName("抖动探测：同输入三次，选中能力可以变，但不得跨到无确认执行")
    void repeatedLiveRunsMayDisagreeButStayInsideTheFence() {
        EvalCase c = caseById("creditcard.howmuch");
        AssetBundle bundle = FastPathLiveFixture.assets();
        List<String> observed = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            FastPathEvalRunner.Actual actual = run(c);
            observed.add(actual.decision() + "/" + actual.reasonCode() + "/" + actual.capability());

            if (actual.capability() != null) {
                var card = bundle.capability(actual.capability());
                if (card != null && card.riskLevel() == RiskLevel.R2) {
                    assertThat(actual.decision()).isNotEqualTo("EXECUTE_CAPABILITY");
                }
            }
        }

        System.out.println("  「这个月要还多少」三次实测：" + observed);
        long distinct = observed.stream().distinct().count();
        if (distinct > 1) {
            System.out.println("  ⚠️ 同输入 " + distinct + " 种结论。仲裁是 temperature=0，"
                    + "所以抖的不是采样，更可能是候选分数在边界上");
        }
        assertThat(observed).hasSize(3);
    }

    @Test
    @DisplayName("检索变强不得换来 R2 直出：转账仍须确认")
    void r2StillRequiresConfirmation() {
        FastPathEvalRunner.Actual actual = run(caseById("transfer.with-slots"));
        assertThat(actual.capability()).isEqualTo("cap.transfer");
        assertThat(actual.reasonCode()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    @DisplayName("强规则仍在 L2 短路：召回再准也不该为一个已确定的答案去问模型")
    void strongRuleStillShortCircuits() {
        FastPathEvalRunner.Actual actual = run(caseById("strongrule.credit-limit"));
        assertThat(actual.shortCircuit()).isEqualTo("L2_STRONG_RULE");
        assertThat(actual.reasonCode()).isEqualTo("POLICY_BLOCK");
    }

    @Test
    @DisplayName("越界问句不得因为检索更宽而变成可执行")
    void outOfScopeNeverBecomesExecutable() {
        for (String id : List.of("outofscope.weather", "outofscope.greeting")) {
            assertThat(run(caseById(id)).decision())
                    .as("用例 %s", id)
                    .isNotEqualTo("EXECUTE_CAPABILITY");
        }
    }

    @Test
    @DisplayName("缺槽仍要问：换卡不得被检索的高分喂成直出")
    void missingSlotsStillClarify() {
        FastPathEvalRunner.Actual actual = run(caseById("card.replace.bare"));
        assertThat(actual.decision()).isEqualTo("CLARIFY");
        assertThat(actual.missingSlots()).containsExactly("cardType");
    }

    @Test
    @DisplayName("条件依赖仍转慢路径")
    void conditionalMultiTaskStillSlowPath() {
        assertThat(run(caseById("multitask.conditional")).decision()).isEqualTo("STATIC_PLAN");
    }

    @Test
    @DisplayName("那两条转账缺口：确认它们到底是不是降级态专属")
    void transferGapsUnderLiveRecall() {
        Map<String, FastPathEvalRunner.Actual> results = Map.of(
                "transfer.other-payee", run(caseById("transfer.other-payee")),
                "transfer.thousand-separator", run(caseById("transfer.thousand-separator")));

        results.forEach((id, actual) -> System.out.printf(
                "  %s → %s%n", id, FastPathEvalRunner.asYamlExpect(actual)));

        // 判定的落点是「不再是无候选」，而不是「必须直出」。
        // 召回回来之后落 CLARIFY 或待确认都是对的——一句转账请求被**拒绝**才是不能接受的
        results.forEach((id, actual) -> assertThat(actual.reasonCode())
                .as("用例 %s 在真检索下仍判无候选，说明这不是降级态专属缺口，"
                        + "而是资产在任何配置下都召不回这种问法", id)
                .isNotEqualTo("NO_CANDIDATE"));
    }

    @Test
    @DisplayName("向量通道未开时要说清：这一档的结论也还不是全通道结论")
    void reportsWhetherSemanticChannelWasActuallyOn() {
        Optional<String> caveat = vectorsIndexed
                ? Optional.empty()
                : Optional.of("向量通道未开，本档只验证了 BM25 字面通道");
        caveat.ifPresent(System.out::println);
        assertThat(readiness.get().searchable()).isTrue();
    }
}
