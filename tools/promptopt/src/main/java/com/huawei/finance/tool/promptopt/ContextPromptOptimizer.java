package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayConfiguration;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.gateway.OpenAiCompatibleModelGateway;
import com.huawei.finance.registry.asset.AssetLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline optimizer for context-rewrite prompts using the production logical model contract. */
public final class ContextPromptOptimizer {

    private ContextPromptOptimizer() {
    }

    public static void main(String[] args) throws Exception {
        int maxRounds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        boolean resume = args.length > 1 && "resume".equalsIgnoreCase(args[1]);
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺 DEEPSEEK_API_KEY：context 优化必须使用线上同一模型");
        }

        Path assetsPath = Path.of("../../agents/mobile-banking-assistant/assets");
        var bundle = new AssetLoader(new ContractValidator()).load(assetsPath);
        Path skillPath = assetsPath.resolve("prompts/context-rewrite-skill.yaml");
        Path trajectoryPath = Path.of(
                "../../agents/mobile-banking-assistant/eval/context-rewrite-trajectories.json");
        SkillFile skill = SkillFile.read(skillPath);
        List<ContextTrajectory> trajectories = loadTrajectories(trajectoryPath, bundle.assetVersion());

        ModelGatewayProperties scorerProps = properties(8_000);
        ContextRewriteScorer scorer = new ContextRewriteScorer(
                gateway(scorerProps, apiKey), scorerProps, new ContractValidator());
        ModelGatewayProperties editorProps = properties(180_000);
        // Thinking responses carry a separate reasoning_content field. The production SSE parser
        // intentionally aggregates only final content, so offline prompt editing uses one JSON response.
        editorProps.getArbitration().setStreamTiming(false);
        ContextPromptEditor editor = new ContextPromptEditor(
                gateway(editorProps, apiKey), editorProps.getArbitration().getModel());

        String best = skill.system();
        ContextRewriteScorer.Score bestScore = scorer.score(best, trajectories);
        ContextRewriteScorer.Score baseline = bestScore;
        System.out.printf("context 轨迹 %d 条，资产 %s，提示词 %s%n现状成绩：%s%n",
                trajectories.size(), bundle.assetVersion(), skill.version(), baseline);
        if (baseline.failures().isEmpty()) {
            System.out.println("冻结轨迹已经全对，不生成过拟合候选。");
            return;
        }

        String lastChanges = null;
        Path candidatePath = Path.of("out/context-rewrite-skill.candidate.yaml");
        if (resume && Files.isRegularFile(candidatePath)) {
            String candidateFile = Files.readString(candidatePath);
            boolean sameAssets = candidateFile.contains("# 轨迹资产：" + bundle.assetVersion());
            SkillFile previous = SkillFile.read(candidatePath);
            var textRuling = ContextOptimizerGuard.reviewText(previous.system());
            // A candidate may predate an expanded trajectory set. Full re-scoring below is the
            // authority; file timestamps are not, and stale behavior cannot pass the gain gate.
            ContextRewriteScorer.Score resumedScore = scorer.score(previous.system(), trajectories);
            var behaviourRuling = ContextOptimizerGuard.reviewBehaviour(baseline, resumedScore);
            if (!sameAssets || !textRuling.accepted()
                    || !behaviourRuling.accepted()) {
                throw new IllegalStateException("已有候选不可续跑：资产一致=" + sameAssets
                        + " 文本门=" + textRuling.reasons()
                        + " 行为门=" + behaviourRuling.reasons());
            }
            best = previous.system();
            bestScore = resumedScore;
            lastChanges = "从已有候选续跑，本轮尚无新增采纳";
            System.out.println("从已有候选续跑：" + resumedScore);
            System.out.println("剩余失败：" + resumedScore.failures().stream()
                    .map(failure -> failure.caseId() + "(" + failure.note() + ")").toList());
        }

        Path feedbackPath = Path.of("out/context-rewrite.feedback.txt");
        String previousRuling = resume && Files.isRegularFile(feedbackPath)
                ? Files.readString(feedbackPath).trim() : null;
        for (int round = 1; round <= maxRounds; round++) {
            System.out.printf("%n--- context 第 %d 轮 ---%n", round);
            var proposal = editor.propose(best, bestScore.failures(), previousRuling);
            if (proposal.isEmpty()) {
                System.out.println("编辑器没有给出合格式候选");
                previousRuling = appendRuling(previousRuling,
                        "最终答案未按 ===CHANGES=== / ===PROMPT=== 协议输出");
                Files.writeString(feedbackPath, previousRuling);
                continue;
            }
            var edit = proposal.get();
            System.out.println("提案改动：\n" + edit.changes());
            var textRuling = ContextOptimizerGuard.reviewText(edit.prompt());
            if (!textRuling.accepted()) {
                System.out.println("文本门拒绝：" + textRuling.reasons());
                previousRuling = appendRuling(previousRuling,
                        "文本门拒绝：" + textRuling.reasons());
                Files.writeString(feedbackPath, previousRuling);
                continue;
            }
            ContextRewriteScorer.Score candidate = scorer.score(edit.prompt(), trajectories);
            System.out.println("候选成绩：" + candidate);
            var behaviour = ContextOptimizerGuard.reviewBehaviour(bestScore, candidate);
            if (!behaviour.accepted()) {
                System.out.println("行为门拒绝：" + behaviour.reasons());
                previousRuling = appendRuling(previousRuling,
                        "行为门拒绝：" + behaviour.reasons() + "；该候选成绩=" + candidate);
                Files.writeString(feedbackPath, previousRuling);
                continue;
            }
            best = edit.prompt();
            bestScore = candidate;
            lastChanges = edit.changes();
            previousRuling = null;
            Files.deleteIfExists(feedbackPath);
            System.out.println("采纳为当前最好");
        }

        if (best.equals(skill.system())) {
            System.out.println("没有候选通过全部门，保留现状。");
            return;
        }
        Path out = writeCandidate(skillPath, skill.version(), best, baseline, bestScore,
                bundle.assetVersion(), lastChanges);
        System.out.println("候选已写入 " + out.toAbsolutePath().normalize() + "，尚未生效。");
    }

    private static String appendRuling(String previous, String current) {
        if (previous == null || previous.isBlank()) {
            return current;
        }
        return previous + "；" + current;
    }

    private static ModelGatewayProperties properties(int timeoutMs) {
        ModelGatewayProperties props = new ModelGatewayProperties();
        props.setBaseUrl("https://api.deepseek.com");
        props.getArbitration().setModel("deepseek-v4-flash");
        props.getArbitration().setTimeoutMs(timeoutMs);
        props.getContextRewrite().setModel("deepseek-v4-flash");
        props.getContextRewrite().setMaxTokens(768);
        props.getContextRewrite().setTemperature(0.0);
        props.getContextRewrite().setTimeoutMs(timeoutMs);
        return props;
    }

    private static ModelGatewayClient gateway(ModelGatewayProperties props, String apiKey) {
        ModelGatewayConfiguration config = new ModelGatewayConfiguration();
        var cm = config.modelGatewayConnectionManager(props);
        var httpClient = config.modelGatewayHttpClient(cm, props);
        var restClient = config.modelGatewayRestClient(httpClient, props);
        var cb = config.modelGatewayCircuitBreaker(config.modelGatewayCircuitBreakerRegistry(props));
        var retry = config.modelGatewayRetry(config.modelGatewayRetryRegistry(props));
        return new OpenAiCompatibleModelGateway(
                restClient, props, cb, retry, new SimpleMeterRegistry(), apiKey);
    }

    private static List<ContextTrajectory> loadTrajectories(Path path, String assetVersion)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CollectionType type = mapper.getTypeFactory()
                .constructCollectionType(List.class, ContextTrajectory.class);
        List<ContextTrajectory> trajectories = mapper.readValue(Files.readString(path), type);
        if (trajectories.isEmpty()) throw new IllegalStateException("context 轨迹为空");
        if (trajectories.stream().anyMatch(item -> !assetVersion.equals(item.assetVersion()))) {
            throw new IllegalStateException("context 轨迹资产版本与当前资产不一致，请先重新 capture");
        }
        return trajectories;
    }

    private static Path writeCandidate(
            Path source, String version, String prompt, ContextRewriteScorer.Score baseline,
            ContextRewriteScorer.Score score, String assetVersion, String changes) throws Exception {
        Path output = Path.of("out/context-rewrite-skill.candidate.yaml");
        Files.createDirectories(output.getParent());
        String header = """
                # promptopt 生成的 context-rewrite 候选，尚未生效。
                # 基线：%s
                # 候选：%s
                # 轨迹资产：%s
                # 改动：%s
                # 只可人工审阅后替换 %s 的 system，并手工升级 version %s。
                version: "%s（待人工升位）"
                system: |
                """.formatted(baseline, score, assetVersion,
                changes == null ? "" : changes.replace('\n', ' '), source.getFileName(), version, version);
        String indented = prompt.lines().map(line -> line.isBlank() ? "" : "  " + line)
                .reduce((a, b) -> a + "\n" + b).orElse("");
        Files.writeString(output, header + indented + "\n");
        return output;
    }
}
