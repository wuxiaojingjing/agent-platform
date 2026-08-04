package com.huawei.finance.fastpath.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 评测种子集（FP-52）。
 *
 * <p>放在 Agent 自己的 {@code eval/} 而不是 {@code src/test/resources}：
 * 它和资产一样是**业务方要改的东西**，埋进某个模块的测试资源目录里，业务方连找都找不到。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalSet {

    /** 当前 Agent 根下的评测集，不依赖仓库根布局。 */
    public static final Path DEFAULT_PATH =
            AgentAssetLocations.requireAgentHome().resolve("eval/fastpath-seed.yaml");

    private String version = "unset";
    private List<EvalCase> cases = List.of();

    public static EvalSet load() {
        return load(DEFAULT_PATH);
    }

    public static EvalSet load(Path path) {
        try {
            return new ObjectMapper(new YAMLFactory())
                    .readValue(Files.readString(path), EvalSet.class);
        } catch (IOException e) {
            // 评测集读不到必须是硬失败：静默跳过会让「评测通过」退化成「评测没跑」，
            // 而后者在报告里和前者长得一模一样
            throw new UncheckedIOException("评测集读取失败：" + path.toAbsolutePath(), e);
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<EvalCase> getCases() {
        return cases;
    }

    public void setCases(List<EvalCase> cases) {
        this.cases = cases == null ? List.of() : List.copyOf(cases);
    }
}
