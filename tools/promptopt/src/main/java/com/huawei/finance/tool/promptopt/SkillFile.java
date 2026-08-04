package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读 {@code assets/prompts/arbitration-skill.yaml} 的 system 段，写候选到旁边的新文件。
 *
 * <p><b>不原地改。</b>工具永远只产出 {@code arbitration-skill.candidate.yaml}，由人看过、
 * 跑过评测再决定要不要替换。理由是提示词是**面客行为的直接来源**：一个自动优化器夜里把它改了、
 * CI 绿着、没人看过那段文字，等于让模型自己决定银行怎么跟客户说话。工具能提出候选，
 * 不能拿到发布权。
 *
 * <p>另外，version 字段必须人工升——资产版本参与出口缓存键与稳定性评测的记录口径，
 * 让工具自动升版本会让「这个结论是哪版提示词跑出来的」失去追溯性。
 */
public final class SkillFile {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path source;
    private final String system;
    private final String version;

    private SkillFile(Path source, String system, String version) {
        this.source = source;
        this.system = system;
        this.version = version;
    }

    public static SkillFile read(Path file) throws IOException {
        JsonNode root = YAML.readTree(Files.readString(file));
        String system = root.path("system").asText("");
        if (system.isBlank()) {
            throw new IllegalStateException("没读到 system 段：" + file.toAbsolutePath());
        }
        return new SkillFile(file, system, root.path("version").asText(""));
    }

    public String system() {
        return system;
    }

    public String version() {
        return version;
    }

    /**
     * 把候选写到**工具自己的 out/ 目录**，并在文件头写清它是什么、下一步做什么。
     *
     * <p>不写进 {@code assets/prompts/}。那个目录是运行时资产，{@code AssetLoader} 在读它，
     * 往里丢一个开发态产物是在赌加载器不会去解析它——今天不解析，哪天加载逻辑改成扫目录就解析了，
     * 而那时故障现场是「应用起不来」，跟一个几周前的工具输出联系不到一起。
     *
     * <p>注释里带上成绩与轨迹版本，是因为这个文件会在仓库里待上几天才有人看。
     * 到那时「这段文字比现状好在哪」如果不在文件里，就只能去翻当时的终端输出。
     */
    public Path writeCandidate(String candidateSystem, ArbitrationScorer.Score baseline,
                               ArbitrationScorer.Score proposed, String assetVersion)
            throws IOException {
        Path out = Path.of("out").resolve(
                source.getFileName().toString().replace(".yaml", ".candidate.yaml"));
        Files.createDirectories(out.getParent());
        String header = """
                # 提示词优化工具产出的候选，**尚未生效**。
                #
                # 现状：%s
                # 候选：%s
                # 轨迹资产版本：%s
                #
                # 要采用的话：
                #   1. 逐字读一遍下面的 system 段，特别是【不可违反的规则】那几条；
                #   2. 只把 system 段贴回 %s，**不要整文件覆盖**——
                #      这里没有 user 段（优化只动 system），整文件覆盖会把占位符模板一起弄丢，
                #      而那会让候选清单、日期基准、已抽取参数全部不再进 prompt；
                #   3. 手工把 version 从 %s 升一位；
                #   4. 重跑快路径全量用例与两档评测。
                #
                # 不要把这个文件直接改名上线：工具只在冻结轨迹上验证过它，
                # 而冻结轨迹里没有阈值、缓存、强规则短路这些东西。

                version: "%s（待人工升位）"

                system: |
                """.formatted(baseline, proposed, assetVersion,
                source.getFileName(), version, version);

        String indented = candidateSystem.lines()
                .map(line -> line.isBlank() ? "" : "  " + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        Files.writeString(out, header + indented + "\n");
        return out;
    }
}
