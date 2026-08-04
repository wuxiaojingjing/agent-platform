package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 读冻结轨迹，并在读的时候就把「轨迹过期」这件事拦下来。 */
public final class TrajectoryStore {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 加载并校验资产版本。
     *
     * <p>版本不一致就直接抛，不给「警告一声继续跑」的选项。因为继续跑是有产出的——
     * 它会输出一份看起来很正常的优化报告，而那份报告优化的是一个**已经不存在的召回态**。
     * 一个静默给出错误结论的工具，比一个跑不起来的工具坏得多。
     *
     * @param currentAssetVersion 当前资产版本，取 {@code AssetBundle.assetVersion()}
     */
    public List<Trajectory> load(Path file, String currentAssetVersion) throws IOException {
        if (!Files.exists(file)) {
            throw new IllegalStateException("找不到轨迹文件 " + file.toAbsolutePath()
                    + "。先跑 intent-fastpath 的 TrajectoryCaptureTest（需要 OpenSearch 与 API key）");
        }
        CollectionType type = mapper.getTypeFactory()
                .constructCollectionType(List.class, Trajectory.class);
        List<Trajectory> all = mapper.readValue(Files.readString(file), type);
        if (all.isEmpty()) {
            throw new IllegalStateException("轨迹文件是空的：" + file.toAbsolutePath());
        }

        Set<String> versions = all.stream().map(Trajectory::assetVersion).collect(
                java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (versions.size() > 1) {
            throw new IllegalStateException("轨迹文件里混了多个资产版本 " + versions
                    + "：这份文件是一次性重录的产物，混版说明它被手工拼过");
        }
        String recorded = versions.iterator().next();
        if (currentAssetVersion != null && !currentAssetVersion.equals(recorded)) {
            throw new IllegalStateException("轨迹是资产 " + recorded + " 时录的，当前资产是 "
                    + currentAssetVersion + "。资产改过召回就变了，冻结的候选已是历史，请重录轨迹");
        }
        return all;
    }
}
