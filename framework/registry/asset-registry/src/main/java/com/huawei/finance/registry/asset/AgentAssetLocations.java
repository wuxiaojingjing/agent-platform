package com.huawei.finance.registry.asset;

import com.huawei.finance.stability.Api;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Agent 资产定位。生产环境通过 {@code huawei.finance.agent.home} 或 {@code AGENT_HOME}
 * 显式指定 Agent 根目录；本地 Maven Reactor 只允许使用 Maven 声明的根目录回退。
 *
 * <p>资产放在 Git 仓里而不是 jar 内（实施架构 §5.1），所以「资产在哪」是个运行期路径问题，
 * 而各模块此前一律写 {@code Path.of("../assets")}——那等于把「本模块在 reactor 的第几层」
 * 编进了每一个调用点。目录重构后这类路径会整批失效，且编译期没有任何征兆。
 *
 * <p>当前资产根是具体 Agent 的 {@code assets/}（含 manifest）；其他 Agent 资产通过
 * {@code agents/&lt;id&gt;/assets/} 发现并合并。资产定位属于 Registry 责任，因此与加载、校验一起收口在
 * {@code asset-registry}。见 {@link #allAssetRoots()}。
 */
@Api
public final class AgentAssetLocations {

    public static final String AGENT_HOME_PROPERTY = "huawei.finance.agent.home";
    public static final String AGENT_HOME_ENV = "AGENT_HOME";
    public static final String ASSETS_PATH_PROPERTY = "huawei.finance.agent.assets.path";
    public static final String ASSETS_PATH_ENV = "HUAWEI_FINANCE_AGENT_ASSETS_PATH";
    public static final String LEGACY_ASSETS_PATH_ENV = "AGENT_ASSETS_PATH";
    private static final String SENTINEL = "manifest.yaml";

    private AgentAssetLocations() {
    }

    /**
     * 根据显式 Agent home 或 Maven Reactor 开发回退定位 {@code assets/}，找不到返回空。
     *
     * <p>认 {@code assets/manifest.yaml} 而不是「目录名叫 assets」：好几个模块的
     * {@code src/test/resources/assets} 也叫这个名字，只认目录名会在模块目录下就停住，
     * 拿到一份夹具资产当成仓库资产用。
     *
     * <p>返回的是<strong>共享根</strong>（规则、模板、域表等）；域内能力卡见
     * {@link #discoverAgentAssetRoots()}。
     */
    public static Optional<Path> findAssets() {
        Optional<Path> configured = configuredAssetsPath();
        if (configured.isPresent()) {
            return configured.filter(path -> Files.isRegularFile(path.resolve(SENTINEL)));
        }
        return findAgentHome().flatMap(AgentAssetLocations::assetsUnder);
    }

    private static Optional<Path> configuredAssetsPath() {
        String configured = System.getProperty(ASSETS_PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ASSETS_PATH_ENV);
        }
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(LEGACY_ASSETS_PATH_ENV);
        }
        return configured == null || configured.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(configured).toAbsolutePath().normalize());
    }

    public static Optional<Path> findAgentHome() {
        Optional<Path> configured = configuredAgentHome();
        if (configured.isPresent()) {
            return configured.filter(home -> Files.isRegularFile(home.resolve("agent.yaml")));
        }

        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("agent.yaml"))) {
            return Optional.of(current);
        }

        // Maven 提供的显式 Reactor 根只用于本地构建，不做任意父目录扫描。
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        if (reactor != null && !reactor.isBlank()) {
            Path developmentAgent = Path.of(reactor).toAbsolutePath().normalize()
                    .resolve("agents/mobile-banking-assistant");
            if (Files.isRegularFile(developmentAgent.resolve("agent.yaml"))) {
                return Optional.of(developmentAgent);
            }
        }
        return Optional.empty();
    }

    public static Path requireAgentHome() {
        return findAgentHome().orElseThrow(() -> new IllegalStateException(
                "找不到 Agent 目录；请设置 " + AGENT_HOME_PROPERTY + " 或 " + AGENT_HOME_ENV));
    }

    private static Optional<Path> configuredAgentHome() {
        String configured = System.getProperty(AGENT_HOME_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(AGENT_HOME_ENV);
        }
        return configured == null || configured.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(configured).toAbsolutePath().normalize());
    }

    private static Optional<Path> assetsUnder(Path agentHome) {
        Path assets = agentHome.resolve("assets").toAbsolutePath().normalize();
        return Files.isRegularFile(assets.resolve(SENTINEL)) ? Optional.of(assets) : Optional.empty();
    }

    /**
     * 同 {@link #findAssets()}，但找不到就抛。
     *
     * <p>需要资产才能工作的模块该用这个：拿不到资产而继续启动，得到的是一份空能力表，
     * 表现成「什么都不支持」而不是一个显式错误。
     */
    public static Path requireAssets() {
        return findAssets().orElseThrow(() -> new IllegalStateException(
                "找不到 Agent 资产；请设置 " + AGENT_HOME_PROPERTY + " 或 " + AGENT_HOME_ENV
                        + " 指向包含 agent.yaml 与 assets/ 的 Agent 目录"));
    }

    /**
     * 仓库根，即共享 {@code assets/} 所在的那一层。
     *
     * <p>给 {@code eval/}、{@code console/} 这些与 {@code assets/} 并列的目录用——
     * 它们和资产一样是业务方要改的东西，同样放在仓库里而不是某个模块下。
     */
    public static Path requireRepoRoot() {
        Path agentHome = requireAssets().getParent();
        Path agentsDir = agentHome.getParent();
        return agentsDir != null && "agents".equals(agentsDir.getFileName().toString())
                ? agentsDir.getParent()
                : agentHome;
    }

    /**
     * 扫描仓库根下 {@code agents/<id>/assets}：目录存在即收录（可不含 pom，asset-only 壳也算）。
     *
     * @param sharedRoot 共享资产根；其父目录视为仓库根
     */
    public static List<Path> discoverAgentAssetRoots(Path sharedRoot) {
        Path agentHome = sharedRoot.toAbsolutePath().normalize().getParent();
        Path parent = agentHome.getParent();
        Path agentsDir = parent != null && "agents".equals(parent.getFileName().toString())
                ? parent
                : agentHome.resolve("agents");
        if (!Files.isDirectory(agentsDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(agentsDir)) {
            return children
                    .filter(Files::isDirectory)
                    .map(agentDir -> agentDir.resolve("assets"))
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.toAbsolutePath().toString()))
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描 agents/*/assets 失败：" + agentsDir, e);
        }
    }

    /** 相对当前工作目录发现的 Agent 资产根。 */
    public static List<Path> discoverAgentAssetRoots() {
        return discoverAgentAssetRoots(requireAssets());
    }

    /**
     * 共享根在前，Agent 根按路径排序在后。
     *
     * @param sharedRoot 共享资产根（含 manifest）
     */
    public static List<Path> allAssetRoots(Path sharedRoot) {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        roots.add(sharedRoot.toAbsolutePath().normalize());
        roots.addAll(discoverAgentAssetRoots(sharedRoot));
        return List.copyOf(roots);
    }

    /** 相对当前工作目录的全部资产根。 */
    public static List<Path> allAssetRoots() {
        return allAssetRoots(requireAssets());
    }

    /**
     * 在全部资产根下查找相对路径文件（共享根优先）。
     *
     * <p>用于菜单元数据等「可能住在入口 Agent 资产里」的文件，避免调用方写死根路径。
     */
    public static Optional<Path> findInAssetRoots(String relative) {
        return findAssets().flatMap(shared -> findInAssetRoots(shared, relative));
    }

    public static Optional<Path> findInAssetRoots(Path sharedRoot, String relative) {
        for (Path root : allAssetRoots(sharedRoot)) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
