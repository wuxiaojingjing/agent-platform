package com.huawei.finance.agent.promptopt;

import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayConfiguration;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.OpenAiCompatibleModelGateway;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 仲裁提示词优化器的入口。
 *
 * <pre>
 * 前置：
 *   1. cd agent-platform
 *      mvn -pl framework/bom/agent-bom,framework/contracts/agent-api,framework/registry/asset-registry,infrastructure/model/model-openai-compatible -am install -DskipTests
 *   2. 冻结轨迹（要 OpenSearch 与 API key）：
 *      mvn -pl intent-fastpath test -Dtest=TrajectoryCaptureTest
 * 跑：
 *   cd tools/promptopt
 *   SILICONFLOW_API_KEY=... mvn -q compile exec:java \
 *     -Dexec.mainClass=com.huawei.finance.tool.promptopt.PromptOptimizer -Dexec.args="3"
 * </pre>
 *
 * <p>参数只有一个：最多试几轮。默认 3。轮数是**钱**——每轮要在全部轨迹上跑一遍仲裁，
 * 再加一次编辑调用。这也是为什么不做「跑到收敛」：一个没有预算上限的优化循环，
 * 忘在后台跑一夜就是一张账单。
 *
 * <p>循环的形状是刻意保守的**爬山**：每轮都从当前最好的那版出发，候选不过门就丢掉、
 * 重新提案，绝不为了「探索」而接受一版更差的。提示词不是搜索空间里的一个点，
 * 它是要上线给客户看的文字；这里宁可少涨两个点，也不要接受一版说不清哪里变了的候选。
 *
 * <p>产出是一个候选文件，不是一次上线。见 {@link SkillFile#writeCandidate}。
 */
public final class PromptOptimizer {

    private PromptOptimizer() {
    }

    public static void main(String[] args) throws Exception {
        int maxRounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;

        String apiKey = System.getenv("SILICONFLOW_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("缺 SILICONFLOW_API_KEY：优化器要真调模型才能打分，没有密钥无事可做");
            System.exit(2);
        }

        Path assets = Path.of("../../agents/mobile-banking-assistant/assets");
        AssetBundle bundle = new AssetLoader(new ContractValidator()).load(assets);
        Path skillPath = assets.resolve("prompts/arbitration-skill.yaml");
        SkillFile skill = SkillFile.read(skillPath);

        List<Trajectory> trajectories = new TrajectoryStore()
                .load(Path.of("../../agents/mobile-banking-assistant/eval/trajectories.json"), bundle.assetVersion());

        Set<String> r2 = bundle.capabilities().stream()
                .filter(c -> c.riskLevel() == RiskLevel.R2)
                .map(com.huawei.finance.contracts.model.CapabilityCard::capabilityId)
                .collect(Collectors.toSet());

        // 打分用线上那套超时（5s）：这里就是要复现线上的仲裁调用，包括它的时间预算。
        // 一次超时在这里被记成失败，与线上被记成降级回退是同一件事
        ModelGatewayProperties props = new ModelGatewayProperties();
        ArbitrationScorer scorer = new ArbitrationScorer(
                buildGateway(props, apiKey), props, new ContractValidator(), r2);

        // 编辑用另一套：它要生成整段提示词（数千字），5s 必超时。
        // 实测第一版就是每轮都超时、每轮都被记成「编辑器没给出提案」——
        // 看起来像模型不配合，其实是拿面客链路的预算去量一件离线工作
        ModelGatewayProperties editorProps = new ModelGatewayProperties();
        editorProps.getArbitration().setTimeoutMs(180_000);
        PromptEditor editor = new PromptEditor(
                buildGateway(editorProps, apiKey), editorProps.getArbitration().getModel());

        System.out.printf("轨迹 %d 条，资产 %s，提示词 %s%n",
                trajectories.size(), bundle.assetVersion(), skill.version());

        String best = skill.system();
        ArbitrationScorer.Score bestScore = scorer.score(best, trajectories);
        ArbitrationScorer.Score baseline = bestScore;
        System.out.println("现状成绩：" + baseline);
        if (baseline.failures().isEmpty()) {
            System.out.println("冻结轨迹上已全对。要继续提升就得先扩充轨迹或补真值标注，"
                    + "在这份轨迹上再优化只会过拟合到这几条");
            return;
        }

        String bestChanges = null;
        for (int round = 1; round <= maxRounds; round++) {
            System.out.printf("%n--- 第 %d 轮 ---%n", round);
            Optional<PromptEditor.Edit> proposal = editor.propose(best, bestScore.failures());
            if (proposal.isEmpty()) {
                System.out.println("编辑器没给出可用提案（网关不可用或未按格式输出），跳过本轮");
                continue;
            }
            PromptEditor.Edit edit = proposal.get();
            System.out.println("提案改动：\n" + edit.changes());

            OptimizerGuard.Ruling text = OptimizerGuard.reviewText(edit.prompt());
            if (!text.accepted()) {
                // 这类拒绝要打出来。它记录的是「优化器试图用什么方式换分数」，
                // 而那份记录比分数本身更值得看
                System.out.println("✗ 文本门拒绝：" + text.reasons());
                continue;
            }

            ArbitrationScorer.Score candidate = scorer.score(edit.prompt(), trajectories);
            System.out.println("候选成绩：" + candidate);
            OptimizerGuard.Ruling behaviour = OptimizerGuard.reviewBehaviour(bestScore, candidate);
            if (!behaviour.accepted()) {
                System.out.println("✗ 行为门拒绝：" + behaviour.reasons());
                continue;
            }

            System.out.println("✓ 采纳为当前最好");
            best = edit.prompt();
            bestScore = candidate;
            bestChanges = edit.changes();
        }

        if (best.equals(skill.system())) {
            System.out.println("\n没有候选通过门。现状保留——这不算失败，"
                    + "它说明这几条判错不是靠改提示词能解决的（多半在召回或资产上）");
            return;
        }
        Path out = skill.writeCandidate(best, baseline, bestScore, bundle.assetVersion());
        System.out.printf("%n候选已写入 %s%n最后一次采纳的改动：%n%s%n"
                        + "★ 它还没生效。请逐字读过 system 段、手工升 version、"
                        + "覆盖原文件后重跑全量用例与两档评测。%n",
                out.toAbsolutePath().normalize(), bestChanges);
    }

    private static ModelGatewayClient buildGateway(ModelGatewayProperties props, String apiKey) {
        ModelGatewayConfiguration config = new ModelGatewayConfiguration();
        var cm = config.modelGatewayConnectionManager(props);
        var httpClient = config.modelGatewayHttpClient(cm, props);
        var restClient = config.modelGatewayRestClient(httpClient, props);
        var cb = config.modelGatewayCircuitBreaker(config.modelGatewayCircuitBreakerRegistry(props));
        var retry = config.modelGatewayRetry(config.modelGatewayRetryRegistry(props));
        return new OpenAiCompatibleModelGateway(
                restClient, props, cb, retry, new SimpleMeterRegistry(), apiKey);
    }
}
