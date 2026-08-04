package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.fastpath.eval.Culprit;
import com.huawei.finance.fastpath.eval.EvalCase;
import com.huawei.finance.fastpath.eval.EvalSet;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.RegistryProperties;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * 归因诊断：每条判错，错在召回还是错在仲裁（FP-52）。
 *
 * <p>这一步必须在动手修之前跑。两类问题的修法互相替代不了：召回缺失只能改资产，
 * 仲裁误判只能改提示词。不先归因，最容易发生的是往能力卡里堆样例——因为那最省事——
 * 然后发现没用，再堆更多，最后资产被样例搅浑，而真正的病在提示词里没人碰过。
 *
 * <p>判据只有一条：**真值能力有没有进候选集**。进了却没被选中，模型看得见而没选，
 * 那是判断的问题；没进，模型压根没有这个选项，提示词写成什么样都选不出来。
 *
 * <p>跑在全通道下（真 OpenSearch + 有密钥时接真网关）。降级态下归因没有意义——
 * 那时几乎所有问题都会归到召回，因为召回本来就被关掉了一半。
 */
class FastPathDiagnosisTest {

    private static OpenSearchClient client;
    private static IndexReadiness readiness;
    private static CandidateSearch search;
    private static ModelGatewayClient gateway;
    private static AssetBundle bundle;

    @BeforeAll
    static void buildLiveIndex() throws Exception {
        client = FastPathLiveFixture.connect();
        if (client == null) {
            return;
        }
        bundle = FastPathLiveFixture.assets();
        RegistryProperties props = FastPathLiveFixture.properties();
        gateway = FastPathLiveFixture.realGateway();
        readiness = FastPathLiveFixture.buildIndex(client, props, bundle, gateway);
        search = FastPathLiveFixture.candidateSearch(client, props, readiness);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            FastPathLiveFixture.dropIndices(client);
        }
    }

    @BeforeEach
    void requireLiveStack() {
        assumeTrue(client != null, "OpenSearch 未就绪，跳过归因诊断");
        assumeTrue(gateway != null,
                "无 SILICONFLOW_API_KEY：仲裁走规则回退，此时归因会全部落到召回上，无意义");
    }

    @Test
    @DisplayName("逐条归因并打成报告")
    void attributesEachFailure() {
        Map<Culprit.Kind, List<String>> byKind = new EnumMap<>(Culprit.Kind.class);
        for (Culprit.Kind kind : Culprit.Kind.values()) {
            byKind.put(kind, new ArrayList<>());
        }

        for (EvalCase c : EvalSet.load().getCases()) {
            FastPathEvalRunner.Observation observed = FastPathEvalRunner.observe(
                    FastPathLiveFixture.build(search, gateway), c);

            EvalCase.Expect truth = c.getTruth();
            List<String> diffs = truth == null
                    ? List.of() : FastPathEvalRunner.diffTruth(truth, observed.actual());
            Culprit culprit = Culprit.of(truth, diffs, observed.candidateIds());

            byKind.get(culprit.kind()).add(
                    "  " + c.getId() + "\n      " + culprit.detail()
                            + (diffs.isEmpty() ? "" : "\n      差异 " + diffs)
                            + "\n      实测 " + FastPathEvalRunner.asYamlExpect(observed.actual()));
        }

        System.out.println("\n===== 归因诊断（全通道，对照 truth 栏）=====");
        report("判对", byKind.get(Culprit.Kind.NONE), false);
        report("召回问题 → 改资产（补话术样例/关键词/描述）", byKind.get(Culprit.Kind.RECALL), true);
        report("仲裁问题 → 改提示词（agent-platform-promptopt）", byKind.get(Culprit.Kind.ARBITRATION), true);
        report("真值待业务，不归因", byKind.get(Culprit.Kind.TRUTH_PENDING), true);

        // 只断言归因覆盖了全集。归因结果本身随资产与提示词演进而变，
        // 钉住某个分布只会让正常的改进被判为失败
        int total = byKind.values().stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(EvalSet.load().getCases().size());
    }

    private static void report(String title, List<String> lines, boolean detail) {
        System.out.println(title + "：" + lines.size() + " 条");
        if (detail) {
            lines.forEach(System.out::println);
        }
    }
}
