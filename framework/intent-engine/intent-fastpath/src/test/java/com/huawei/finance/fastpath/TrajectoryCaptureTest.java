package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.fastpath.eval.EvalCase;
import com.huawei.finance.fastpath.eval.EvalSet;
import com.huawei.finance.fastpath.eval.RecordingGateway;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.contracts.port.CandidateSearch;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.index.IndexReadiness;
import com.huawei.finance.registry.index.RegistryProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * 轨迹冻结：把真召回下那一次交给模型的 prompt 原样落盘（{@code eval/trajectories.json}）。
 *
 * <p>这一步的全部意义在于**把召回从提示词优化里摘出去**。不冻结的话，每改一版提示词都要重跑
 * 真召回，于是「这一版变好了」永远答不上一个问题：是提示词的功劳，还是这次召回刚好抖出了
 * 更好的候选？{@link FastPathEvalLiveTest} 已经实测到「这个月要还多少」跨次跑会换能力，
 * 说明这种抖动是真的存在，不是假想的顾虑。在会抖的信号上做优化，就是在噪声上爬坡。
 *
 * <p>产出物是给 {@code tools/promptopt} 用的，而那个工具**不在运行时反应堆里**。
 * 两边的接触面只有这个 JSON 文件——刻意如此：文件是一个可以看、可以 diff、可以提交评审的边界，
 * 而一条 Java 依赖会把开发态工具的依赖带进生产 classpath。
 *
 * <p>只在真检索可用时跑。降级态的候选是规则召回出来的那几张卡，拿它冻出来的轨迹去优化提示词，
 * 优化的是一个线上不存在的输入分布。
 *
 * <p><b>轨迹会过期。</b>资产一改，召回就变，冻结的候选清单就成了历史。因此每条轨迹都带
 * {@code assetVersion}，工具启动时比对；不一致就要重跑这个类。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrajectoryCaptureTest {

    private static final Path OUTPUT =
            AgentAssetLocations.requireAgentHome().resolve("eval/trajectories.json");

    private static OpenSearchClient client;
    private static IndexReadiness readiness;
    private static CandidateSearch search;
    private static ModelGatewayClient realGateway;
    private static AssetBundle bundle;

    @BeforeAll
    static void buildLiveIndex() throws Exception {
        client = FastPathLiveFixture.connect();
        if (client == null) {
            return;
        }
        bundle = FastPathLiveFixture.assets();
        RegistryProperties props = FastPathLiveFixture.properties();
        realGateway = FastPathLiveFixture.realGateway();
        readiness = FastPathLiveFixture.buildIndex(client, props, bundle, realGateway);
        search = FastPathLiveFixture.candidateSearch(client, props, readiness);
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            FastPathLiveFixture.dropIndices(client);
        }
    }

    @BeforeEach
    void requireFullChannel() {
        assumeTrue(client != null, "OpenSearch 未就绪，跳过轨迹冻结");
        assumeTrue(readiness != null && readiness.get().searchable(), "索引未建成，跳过轨迹冻结");
        // 这一条不肯放松：无密钥时仲裁根本不会发生，录不到任何 prompt。
        // 与其落一个空文件让工具以为「轨迹已备好」，不如整类跳过
        assumeTrue(realGateway != null,
                "无 SILICONFLOW_API_KEY：仲裁不会发生，录不到 prompt，跳过轨迹冻结");
    }

    @Test
    @DisplayName("把全通道下的仲裁输入逐字落盘，供提示词优化工具消费")
    void freezesArbitrationPrompts() throws Exception {
        RecordingGateway recorder = new RecordingGateway(realGateway);
        ArrayNode out = ContractJson.mapper().createArrayNode();
        List<String> noArbitration = new ArrayList<>();

        for (EvalCase c : EvalSet.load().getCases()) {
            if (!c.hasTruth()) {
                // 真值待定的用例不冻。让优化器去满足一个没人给过的期望，
                // 它只会去满足自己的猜测，而那个猜测会以「分数提高了」的样子出现
                continue;
            }
            recorder.reset();
            FastPathEvalRunner.run(
                    FastPathLiveFixture.build(search, recorder), c);

            ChatRequest req = recorder.lastArbitration().orElse(null);
            if (req == null) {
                // 强规则短路、无候选、缓存命中都会让仲裁不发生。这不是错，
                // 但要报出来：如果多到离谱，说明这份种子集测不到仲裁
                noArbitration.add(c.getId());
                continue;
            }
            out.add(trajectory(c, req));
        }

        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT,
                ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(out));

        System.out.printf("%n===== 轨迹冻结 =====%n已落盘 %d 条 → %s%n资产版本 %s%n未触发仲裁 %d 条：%s%n",
                out.size(), OUTPUT.toAbsolutePath().normalize(), bundle.assetVersion(),
                noArbitration.size(), noArbitration);

        assertThat(out.size())
                .as("一条轨迹都没录到。要么种子集全被短路，要么仲裁的 jsonMode 判据已失效")
                .isPositive();
    }

    /**
     * 一条轨迹。字段名与 {@code com.huawei.finance.promptopt.Trajectory} 手工对齐——
     * 两边隔着模块边界，靠的是这份 JSON，而不是共享类。
     *
     * <p>{@code capabilityDeclared} 用「真值里写了 capability」来定：种子集目前无法表达
     * 「必须不选任何能力」（不写 = 不校验）。越界问句的这层要求由 decision 承担，
     * 不在这里替业务补一个它没说过的断言。
     */
    private static ObjectNode trajectory(EvalCase c, ChatRequest req) {
        ObjectNode node = ContractJson.mapper().createObjectNode();
        node.put("caseId", c.getId());
        node.put("query", c.getQuery());
        node.put("userPrompt", req.userPrompt());
        node.put("assetVersion", bundle.assetVersion());

        EvalCase.Expect truth = c.getTruth();
        ObjectNode t = node.putObject("truth");
        t.put("decision", truth.getDecision());
        t.put("reasonCode", truth.getReasonCode());
        t.put("capability", truth.getCapability());
        t.put("capabilityDeclared", truth.getCapability() != null);
        if (truth.getMissingSlots() != null) {
            ArrayNode missing = t.putArray("missingSlots");
            truth.getMissingSlots().forEach(missing::add);
        }
        ObjectNode slots = t.putObject("slots");
        truth.getSlots().forEach(slots::put);
        return node;
    }

    /**
     * 顺路守一件事：录制器必须是透明的。
     *
     * <p>它包在真网关外面，如果它改变了任何行为，冻下来的轨迹就不是线上那份，
     * 而整个工具的前提正是「这是线上那份」。
     */
    @Test
    @DisplayName("录制器不改变结论：包与不包，出口一致")
    void recordingIsTransparent() {
        EvalCase c = EvalSet.load().getCases().stream()
                .filter(x -> "transfer.with-slots".equals(x.getId()))
                .findFirst()
                .orElseThrow();

        FastPathEvalRunner.Actual bare = FastPathEvalRunner.run(
                FastPathLiveFixture.build(search, realGateway), c);
        RecordingGateway recorder = new RecordingGateway(realGateway);
        FastPathEvalRunner.Actual wrapped = FastPathEvalRunner.run(
                FastPathLiveFixture.build(search, recorder), c);

        assertThat(wrapped.decision()).isEqualTo(bare.decision());
        assertThat(wrapped.capability()).isEqualTo(bare.capability());
        assertThat(recorder.arbitrationCalls())
                .as("快路径一次交互只该问模型一次仲裁（A 线往返预算）")
                .isEqualTo(1);
    }
}
