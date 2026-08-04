package com.huawei.finance.product.mobilebanking.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.registry.asset.AssetStore;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Read-only console projection of the offline prompt optimization workspace. */
public final class PromptOptimizationView {

    private static final List<ModeDefinition> MODES = List.of(
            new ModeDefinition("arbitration", "入口仲裁与任务形态",
                    "prompts/arbitration-skill.yaml", "trajectories.json",
                    "arbitration-skill.candidate.yaml"),
            new ModeDefinition("context-rewrite", "上下文改写",
                    "prompts/context-rewrite-skill.yaml", "context-rewrite-trajectories.json",
                    "context-rewrite-skill.candidate.yaml"));

    private final AssetStore store;
    private final ObjectMapper json;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final Path agentHome;
    private final List<Path> outputRoots;

    public PromptOptimizationView(AssetStore store) {
        this(store, store.root().toAbsolutePath().normalize().getParent(), null);
    }

    PromptOptimizationView(AssetStore store, Path agentHome, List<Path> outputRoots) {
        this.store = store;
        this.agentHome = agentHome.toAbsolutePath().normalize();
        this.outputRoots = outputRoots == null
                ? defaultOutputRoots(this.agentHome)
                : outputRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    public Snapshot snapshot() {
        String currentAssetVersion = store.current().assetVersion();
        return new Snapshot(currentAssetVersion, false,
                MODES.stream().map(mode -> describe(mode, currentAssetVersion)).toList());
    }

    private Mode describe(ModeDefinition definition, String currentAssetVersion) {
        Path promptPath = store.root().resolve(definition.promptPath()).normalize();
        Path trajectoryPath = agentHome.resolve("eval").resolve(definition.trajectoryFile()).normalize();
        JsonNode prompt = readYaml(promptPath);
        List<Trajectory> trajectories = readTrajectories(trajectoryPath);
        Set<String> versions = new LinkedHashSet<>();
        trajectories.stream().map(Trajectory::assetVersion)
                .filter(value -> value != null && !value.isBlank()).forEach(versions::add);
        boolean stale = trajectories.isEmpty()
                || versions.size() != 1
                || !versions.contains(currentAssetVersion);

        Candidate candidate = findCandidate(
                definition.candidateFile(), trajectoryPath, trajectories.size());
        return new Mode(
                definition.id(), definition.label(), prompt.path("version").asText(""),
                definition.promptPath(), prompt.path("system").asText(""),
                definition.trajectoryFile(), trajectories.size(), List.copyOf(versions), stale,
                trajectories, candidate);
    }

    private List<Trajectory> readTrajectories(Path path) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            JsonNode root = json.readTree(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isArray()) {
                return List.of();
            }
            List<Trajectory> result = new ArrayList<>();
            for (JsonNode item : root) {
                result.add(new Trajectory(
                        item.path("caseId").asText(""),
                        item.path("query").asText(""),
                        item.path("assetVersion").asText(""),
                        json.convertValue(item.path("truth"), Object.class),
                        readHistory(item.path("conversationHistory")),
                        item.path("userPrompt").asText("")));
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取提示词优化轨迹：" + path, e);
        }
    }

    private List<Object> readHistory(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Object> messages = new ArrayList<>();
        node.forEach(item -> messages.add(json.convertValue(item, Object.class)));
        return List.copyOf(messages);
    }

    private Candidate findCandidate(String fileName, Path trajectoryPath, int trajectoryCount) {
        for (Path root : outputRoots) {
            Path candidate = root.resolve(fileName).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String content = Files.readString(candidate, StandardCharsets.UTF_8);
                JsonNode parsed = readYaml(candidate);
                Instant generatedAt = Files.getLastModifiedTime(candidate).toInstant();
                String score = metadata(content, "候选：");
                boolean stale = Files.isRegularFile(trajectoryPath)
                        && Files.getLastModifiedTime(candidate).compareTo(
                                Files.getLastModifiedTime(trajectoryPath)) < 0;
                stale = stale || scoreDenominator(score) != trajectoryCount;
                return new Candidate(true, stale ? "STALE_TRAJECTORIES" : "REVIEW_PENDING",
                        candidate.getFileName().toString(), generatedAt,
                        parsed.path("version").asText(""), firstNonBlank(
                                metadata(content, "现状："), metadata(content, "基线：")),
                        score,
                        metadata(content, "轨迹资产版本："),
                        parsed.path("system").asText(""), content);
            } catch (IOException e) {
                throw new UncheckedIOException("无法读取提示词优化候选：" + candidate, e);
            }
        }
        return Candidate.missing();
    }

    private JsonNode readYaml(Path path) {
        if (!Files.isRegularFile(path)) {
            return yaml.createObjectNode();
        }
        try {
            return yaml.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取提示词文件：" + path, e);
        }
    }

    private static String metadata(String content, String marker) {
        return content.lines()
                .map(String::trim)
                .map(line -> line.startsWith("#") ? line.substring(1).trim() : line)
                .filter(line -> line.startsWith(marker))
                .map(line -> line.substring(marker.length()).trim())
                .findFirst().orElse("");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static int scoreDenominator(String score) {
        if (score == null) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("通过\\s+\\d+/(\\d+)").matcher(score);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static List<Path> defaultOutputRoots(Path agentHome) {
        return List.of(
                Path.of("/opt/promptopt-out"),
                AgentAssetLocations.requireRepoRoot().resolve("tools/promptopt/out").normalize());
    }

    private record ModeDefinition(String id, String label, String promptPath,
                                  String trajectoryFile, String candidateFile) {
    }

    public record Snapshot(String assetVersion, boolean runAvailable, List<Mode> modes) {
    }

    public record Mode(String id, String label, String promptVersion, String promptPath,
                       String currentPrompt, String trajectoryFile, int trajectoryCount,
                       List<String> trajectoryAssetVersions, boolean trajectoriesStale,
                       List<Trajectory> trajectories, Candidate candidate) {
    }

    public record Trajectory(String caseId, String query, String assetVersion,
                             Object truth, List<Object> conversationHistory,
                             String modelInput) {
    }

    public record Candidate(boolean available, String status, String fileName, Instant generatedAt,
                            String version, String baseline, String score, String assetVersion,
                            String prompt, String content) {
        static Candidate missing() {
            return new Candidate(false, "NOT_GENERATED", "", null, "", "", "", "", "", "");
        }
    }
}
