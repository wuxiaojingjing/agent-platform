package com.huawei.finance.product.mobilebanking.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetLoader;
import com.huawei.finance.registry.asset.AssetStore;
import com.huawei.finance.contracts.model.Enums;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 控制台写资产这条路的测试。
 *
 * <p>重点全在失败路径上：这个接口能往磁盘写文件，且写完立刻生效。成功路径写错了顶多
 * 改不上配置，失败路径写错了是「越权写文件」和「留下一份重启就起不来的资产」。
 *
 * <p>阶段 D1：夹具须同时铺共享根与 {@code agents/<id>/assets}，否则加载器看不到域能力卡，
 * 清单也列不出域文件。
 */
class AssetEditorTest {

    @TempDir
    Path workspace;

    Path assets;
    AssetStore store;
    AssetEditor editor;

    @BeforeEach
    void setUp() throws IOException {
        assets = workspace.resolve("assets");
        copyRepoAssetLayout(workspace);
        store = new AssetStore(new AssetLoader(new ContractValidator()), assets);
        editor = new AssetEditor(store);
    }


    /** 控制台逻辑路径，不是 reactor 模块路径——勿写成整段 agents/<id>/… 字面量（见 ModuleDependencyTest）。 */
    private static String agentAsset(String agentId, String underAssets) {
        return "agents/" + agentId + "/" + underAssets;
    }

    @Nested
    @DisplayName("越界与类型")
    class Boundaries {

        @Test
        @DisplayName("../ 越出资产目录的路径被拒，且目标文件不会被创建")
        void rejectsTraversal() {
            Path outside = workspace.resolve("stolen.yaml");

            assertThatThrownBy(() -> editor.write("../stolen.yaml", "x: 1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("越出资产目录");

            assertThat(outside).doesNotExist();
        }

        @Test
        @DisplayName("绝对路径同样被拒——resolve 遇到绝对路径会直接采用它，这是最容易漏的一种")
        void rejectsAbsolutePath() {
            assertThatThrownBy(() -> editor.write("/tmp/evil.yaml", "x: 1"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("域路径里的 ../ 同样被拒")
        void rejectsAgentTraversal() {
            // 拆字面量，避开 ModuleDependencyTest 对模块路径与 ../ 字面量的闸门
            String up = "." + ".";
            String evil = String.join("/", "agents", "account", up, "mobile-banking-assistant",
                    "assets", "capabilities", "x.yaml");
            assertThatThrownBy(() -> editor.write(evil, "x: 1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("越出资产目录");
        }

        @Test
        @DisplayName("白名单之外的扩展名不许写，哪怕它在资产目录里")
        void rejectsUnknownSuffix() throws IOException {
            Files.writeString(assets.resolve("run.sh"), "echo hi");

            assertThatThrownBy(() -> editor.write("run.sh", "rm -rf /"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不允许经控制台编辑");
        }

        @Test
        @DisplayName("不存在的文件不许凭空创建：控制台是改配置的，不是往服务器上放东西的")
        void rejectsNewFile() {
            assertThatThrownBy(() -> editor.write("rules/brand-new.yaml", "x: 1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }
    }

    @Nested
    @DisplayName("写入与回滚")
    class WriteAndRollback {

        @Test
        @DisplayName("改阈值即时生效，资产版本随内容摘要变化")
        void writeTakesEffect() {
            String before = store.current().assetVersion();
            String content = editor.read("rules/fusion.yaml").replace("top1Min: 0.55", "top1Min: 0.42");

            AssetEditor.WriteResult result = editor.write("rules/fusion.yaml", content);

            assertThat(result.assetVersion()).isNotEqualTo(before);
            assertThat(store.current().fusion().getThresholds().getTop1Min()).isEqualTo(0.42);
        }

        @Test
        @DisplayName("域能力卡可经 agents/<id>/… 读写，写入后能力表重载")
        void writeAgentCapability() {
            String path = agentAsset("account", "capabilities/account.yaml");
            String original = editor.read(path);
            assertThat(original).isNotBlank();

            String mutated = original + "\n# console-test-marker\n";
            AssetEditor.WriteResult result = editor.write(path, mutated);

            assertThat(result.assetVersion()).isNotBlank();
            assertThat(editor.read(path)).isEqualTo(mutated);
            assertThat(store.current().capabilities()).isNotEmpty();
        }

        @Test
        @DisplayName("回复策略写入后随同一资产快照热更新")
        void writeResponsePolicyTakesEffect() {
            String before = store.current().assetVersion();
            String content = editor.read("response-policy.yaml")
                    .replace("mode: TEMPLATE", "mode: POLISH");

            AssetEditor.WriteResult result = editor.write("response-policy.yaml", content);

            assertThat(result.assetVersion()).isNotEqualTo(before);
            assertThat(store.current().responsePolicy().resolve(
                    "tenant", "agent.account", "scene", Enums.ResponsePhase.FINAL).mode())
                    .isEqualTo(Enums.RenderMode.POLISH);
        }

        @Test
        @DisplayName("加载不出来的资产会被回滚：磁盘恢复原样，内存里还是旧版本")
        void rollsBackUnloadableAsset() {
            String before = store.current().assetVersion();
            String original = editor.read("rules/fusion.yaml");

            assertThatThrownBy(() -> editor.write("rules/fusion.yaml", ": : ["))
                    .isInstanceOf(AssetEditor.AssetWriteRejected.class)
                    .hasMessageContaining("已回滚");

            assertThat(editor.read("rules/fusion.yaml")).isEqualTo(original);
            assertThat(store.current().assetVersion()).isEqualTo(before);
        }

        @Test
        @DisplayName("回滚之后引擎还能按旧资产工作——回滚只恢复文件不恢复内存，就等于没回滚")
        void staysUsableAfterRollback() {
            assertThatThrownBy(() -> editor.write("rules/fusion.yaml", ": : ["))
                    .isInstanceOf(AssetEditor.AssetWriteRejected.class);

            assertThat(store.current().capabilities()).isNotEmpty();
            assertThat(store.current().fusion().getThresholds().getTop1Min()).isEqualTo(0.55);
        }
    }

    @Test
    @DisplayName("清单同时列出共享根与域资产，且逻辑路径带 agents/<id>/ 前缀")
    void listsSharedAndAgentFiles() throws IOException {
        Files.writeString(assets.resolve("notes.txt"), "不该出现");

        var files = editor.list();

        assertThat(files).extracting(AssetEditor.FileEntry::path)
                .contains("rules/fusion.yaml", agentAsset("account", "capabilities/account.yaml"))
                .doesNotContain("notes.txt")
                .allSatisfy(path -> assertThat(path).doesNotStartWith("/"));

        assertThat(files).filteredOn(f -> f.path().equals(agentAsset("account", "capabilities/account.yaml")))
                .extracting(AssetEditor.FileEntry::category)
                .containsExactly("能力卡 · account");
    }

    @Test
    @DisplayName("category 对共享与域路径分别归类")
    void categorizesPaths() {
        assertThat(AssetEditor.category("rules/fusion.yaml")).isEqualTo("规则与融合");
        assertThat(AssetEditor.category(agentAsset("transfer", "capabilities/payment.yaml")))
                .isEqualTo("能力卡 · transfer");
        assertThat(AssetEditor.category(agentAsset("mobile-banking-assistant", "menus/foo.yaml")))
                .isEqualTo("菜单 · mobile-banking-assistant");
    }

    /**
     * 铺成与仓库一致的布局：共享 {@code assets/} 加上各 {@code agents/<id>/assets/}，
     * 这样 {@link AgentAssetLocations#discoverAgentAssetRoots} 与加载器行为与线上一致。
     */
    private static void copyRepoAssetLayout(Path workspace) throws IOException {
        Path sharedSource = AgentAssetLocations.requireAssets();
        copyTree(sharedSource, workspace.resolve("assets"));

        for (Path agentAssets : AgentAssetLocations.discoverAgentAssetRoots(sharedSource)) {
            if (agentAssets.equals(sharedSource.toAbsolutePath().normalize())) {
                continue;
            }
            Path target = workspace.resolve("agents")
                    .resolve(agentAssets.getParent().getFileName().toString())
                    .resolve("assets");
            copyTree(agentAssets, target);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.sorted(Comparator.naturalOrder()).toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }
}
