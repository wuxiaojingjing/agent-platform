package com.huawei.finance.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.tck.ReactorLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模块间依赖方向，直接读 pom 判定。
 *
 * <p>为什么不交给 ArchUnit：它读的是字节码依赖，看不见「pom 里声明了、源码一处没用」这种。
 * 而那恰恰是真出过的问题——{@code task-orchestrator} 曾对 {@code mock}
 * 有编译期依赖，orchestrator 源码却一处都没 import 它。字节码里没有痕迹，
 * 任何基于字节码的规则都照样绿，但每家银行部署中控时都会被拖进一个演示实现的 jar，
 * 依赖方向还是倒的（核心依赖实现）。
 *
 * <p>这类污染只会越积越多：没人会去读别的模块的 pom，而多一条依赖既不报错也不变红。
 */
class ModuleDependencyTest {

    private static final Pattern ARTIFACT = Pattern.compile(
            "<artifactId>\\s*([A-Za-z0-9._-]+)\\s*</artifactId>");
    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    /** artifactId → 模块在 reactor 里的相对路径。 */
    private static Map<String, String> modulePaths;
    private static Map<String, TreeSet<String>> dependencies;

    @BeforeAll
    static void readPoms() throws IOException {
        Path root = locateReactorRoot();
        modulePaths = ReactorLayout.modulePaths();

        // 去注释再匹配：本工程的 pom 惯于用注释解释「刻意不依赖某模块」，
        // 而那些解释里就带着模块名。不去掉的话，一条写得越清楚的「不依赖」注释
        // 越会被读成「有依赖」——本文件多条用例都建立在这张表上。
        Map<String, String> texts = new TreeMap<>();
        for (Map.Entry<String, String> e : modulePaths.entrySet()) {
            texts.put(e.getKey(), stripXmlComments(Files.readString(
                    root.resolve(e.getValue()).resolve("pom.xml"), StandardCharsets.UTF_8)));
        }

        dependencies = new TreeMap<>();
        texts.forEach((self, text) -> {
            TreeSet<String> deps = new TreeSet<>();
            Matcher m = ARTIFACT.matcher(text);
            while (m.find()) {
                String found = m.group(1);
                // 只留在册模块：第三方 artifact 不参与依赖方向判定
                if (!found.equals(self) && modulePaths.containsKey(found)) {
                    deps.add(found);
                }
            }
            dependencies.put(self, deps);
        });
        assertThat(dependencies).as("一个模块都没读到，说明路径定位错了").isNotEmpty();
    }

    /** 模块目录，供需要读源码/pom 的用例用：路径写死会在下次搬目录时变成假绿。 */
    private static Path moduleDir(String artifactId) throws IOException {
        return ReactorLayout.moduleDir(artifactId);
    }

    @Test
    @DisplayName("只有示范应用声明对 Mock 领域 Agent 的依赖")
    void onlyTheSampleAppDeclaresMockAgents() {
        var offenders = dependencies.entrySet().stream()
                .filter(e -> e.getValue().contains("mock"))
                .map(Map.Entry::getKey)
                .filter(module -> !module.equals("mobile-banking-assistant") && !module.equals("agent-bom"))
                .toList();
        assertThat(offenders)
                .as("Mock 返回写死的假数据。它出现在哪个模块的 pom 里，"
                        + "哪个模块的使用者就会被动拖进这个 jar")
                .isEmpty();
    }

    @Test
    @DisplayName("库模块不依赖示范应用")
    void librariesDoNotDependOnTheSampleApp() {
        var offenders = dependencies.entrySet().stream()
                .filter(e -> !e.getKey().equals("agent-bom"))
                .filter(e -> e.getValue().contains("mobile-banking-assistant"))
                .map(Map.Entry::getKey)
                .toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("入口 compile 依赖 a2a-client，Gateway 和进程内实现只能用于测试")
    void entryUsesA2aClientNotGatewayCompile() throws IOException {
        String pom = pomOf("mobile-banking-assistant");
        assertThat(scopeOf(pom, "a2a-client"))
                .as("入口须 compile 依赖 a2a-client（v0.4 §10.1 / §12）")
                .isEqualTo("compile");
        assertThat(scopeOf(pom, "a2a-gateway-core"))
                .as("产品进程不得内嵌 Gateway，联调可保留 test 依赖")
                .isIn("test", "absent");
        assertThat(scopeOf(pom, "a2a-inprocess-testkit"))
                .as("进程内 A2A 只属于测试工具")
                .isIn("test", "absent");
    }

    @Test
    @DisplayName("入口经 agent-starter / intent-engine-starter 集成，不得直接依赖 fastpath/slowpath")
    void entryUsesIntentEngineStarter() throws IOException {
        String pom = pomOf("mobile-banking-assistant");
        boolean viaStarter = scopeOf(pom, "intent-engine-starter").equals("compile")
                || scopeOf(pom, "agent-starter").equals("compile");
        assertThat(viaStarter)
                .as("入口须依赖 agent-starter 或 intent-engine-starter")
                .isTrue();
        assertThat(scopeOf(pom, "intent-fastpath"))
                .as("实现细节经 starter 传递，入口 pom 不得直连")
                .isEqualTo("absent");
        assertThat(scopeOf(pom, "intent-slowpath")).isEqualTo("absent");
    }

    @Test
    @DisplayName("入口 compile 依赖 agent-starter")
    void entryDependsOnAgentStarter() throws IOException {
        assertThat(scopeOf(pomOf("mobile-banking-assistant"), "agent-starter")).isEqualTo("compile");
    }

    @Test
    @DisplayName("a2a-client 不依赖 gateway / server 实现")
    void a2aClientStaysThin() {
        assertThat(dependencies.get("a2a-client"))
                .as("client 只能依赖契约与地基，不能回头依赖实现")
                .doesNotContain("a2a-gateway-core", "a2a-gateway-app", "a2a-server",
                        "a2a-inprocess-testkit", "mobile-banking-assistant");
        assertThat(dependencies.get("a2a-client"))
                .as("client 依赖 a2a-api（v0.5 §5.1）")
                .contains("a2a-api");
        assertThat(dependencies.get("a2a-gateway-core"))
                .as("gateway 实现 client 端口并装配 server 节点")
                .contains("a2a-client", "a2a-server");
        assertThat(dependencies.get("a2a-inprocess-testkit"))
                .contains("a2a-client", "a2a-gateway-core");
    }

    @Test
    @DisplayName("v0.5 公开 API 包不依赖 Spring / 中间件 / 平台实现")
    void publicApiPackagesStayThin() {
        for (String api : List.of("agent-api", "task-api", "a2a-api", "intent-engine-api",
                "agent-runtime-api")) {
            assertThat(dependencies.get(api))
                    .as("%s 不得依赖实现或适配器（v0.5 §15.12 / §16）", api)
                    .doesNotContain(
                            "intent-fastpath", "intent-slowpath", "intent-engine-default",
                            "a2a-gateway-core", "a2a-gateway-app", "a2a-server", "a2a-client",
                            "a2a-inprocess-testkit",
                            "cache-redis", "persistence-jdbc", "search-opensearch",
                            "discovery-nacos", "openjiuwen", "task-orchestrator",
                            "context-engine", "response-engine", "mobile-banking-assistant");
        }
        assertThat(dependencies.get("task-api"))
                .as("task-api → agent-api；禁止反向成环")
                .contains("agent-api");
        assertThat(dependencies.get("agent-api"))
                .doesNotContain("task-api");
    }

    @Test
    @DisplayName("intent-engine-starter 经 default 聚合，不直连 fastpath/slowpath")
    void intentEngineStarterUsesDefault() {
        assertThat(dependencies.get("intent-engine-starter"))
                .contains("intent-engine-api", "intent-engine-default")
                .doesNotContain("intent-fastpath", "intent-slowpath");
        assertThat(dependencies.get("intent-engine-default"))
                .contains("intent-engine-api", "intent-fastpath", "intent-slowpath");
    }

    @Test
    @DisplayName("v0.5 agent-runtime：api 薄闭包；入口不直连引擎三件套；starter 聚合 runtime")
    void agentRuntimePackaging() throws IOException {
        assertThat(dependencies.get("agent-runtime-api"))
                .as("runtime-api 不得拖实现 / Spring 适配")
                .doesNotContain(
                        "intent-fastpath", "intent-slowpath", "a2a-gateway", "a2a-server",
                        "cache-redis", "task-orchestrator", "context-engine", "response-engine",
                        "agent-runtime-core", "agent-runtime-starter", "mobile-banking-assistant");
        assertThat(dependencies.get("agent-runtime-core"))
                .contains("agent-runtime-api", "task-orchestrator", "context-engine", "response-engine");
        assertThat(dependencies.get("agent-runtime-starter"))
                .contains("agent-runtime-core", "agent-runtime-api");
        assertThat(dependencies.get("agent-starter"))
                .as("agent-starter 须聚合 runtime-starter（v0.5 §17.3）")
                .contains("agent-runtime-starter");
        String appPom = pomOf("mobile-banking-assistant");
        assertThat(scopeOf(appPom, "task-orchestrator"))
                .as("入口经 runtime-starter 传递，不得直连中控")
                .isEqualTo("absent");
        assertThat(scopeOf(appPom, "context-engine")).isEqualTo("absent");
        assertThat(scopeOf(appPom, "response-engine")).isEqualTo("absent");
    }


    /**
     * 稳定性标注模块必须零 agent-platform 依赖。
     *
     * <p>它被所有模块依赖，自己再依赖任何东西都会变成全工程的强制传递依赖；
     * 依赖到 agent-platform 模块更会直接成环。
     */
    @Test
    @DisplayName("stability-api 不依赖任何其它模块")
    void stabilityModuleHasNoDependencies() {
        assertThat(dependencies.get("stability-api")).isEmpty();
    }

    @Test
    @DisplayName("每个库模块都依赖 stability-api，否则标不了承诺面")
    void everyLibraryDependsOnStability() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String module : modulePaths.keySet()) {
            if (module.equals("stability-api") || module.equals("agent-bom")) {
                continue;
            }
            Path source = moduleDir(module).resolve("src/main/java");
            if (!Files.isDirectory(source)) {
                continue;
            }
            boolean declaresStableSurface;
            try (var files = Files.walk(source)) {
                declaresStableSurface = files.filter(p -> p.toString().endsWith(".java"))
                        .anyMatch(p -> {
                            try {
                                String text = stripJavaComments(Files.readString(p));
                                return text.contains("@Api") || text.contains("@Spi");
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
            if (declaresStableSurface && !dependencies.get(module).contains("stability-api")) {
                missing.add(module);
            }
        }
        assertThat(missing).isEmpty();
    }

    /**
     * 适配层只做向下的 SPI 注册，不得压着中控。
     *
     * <p>这条依赖真存在过：可执行 CapabilityTool 要落回中控，于是整个
     * {@code openjiuwen} 跟着依赖了 {@code task-orchestrator}。后果是只想用模型或向量
     * 适配器的模块——比如领域 Agent——被迫拖进整个中控。慢路径已改为只挂 ProposalTool，
     * 这条守卫防的是执行路径挪回适配层。
     */
    @Test
    @DisplayName("openjiuwen 不依赖中控：适配是向下的，中控是向上的")
    void adaptersDoNotDependOnTheOrchestrator() {
        assertThat(dependencies.get("openjiuwen-adapter"))
                .as("适配层不得压着中控")
                .doesNotContain("task-orchestrator");
    }

    /**
     * 慢路径只规划不执行（ADR-004 / 架构草案阶段 0）。
     *
     * <p>一旦 pom 再声明对中控的依赖，可执行 Tool 很容易回流，intent-engine 也无法
     * 在不拖中控的前提下复用慢路径规划。
     */
    @Test
    @DisplayName("intent-slowpath 不依赖中控：只规划不执行")
    void slowpathDoesNotDependOnTheOrchestrator() {
        assertThat(dependencies.get("intent-slowpath"))
                .as("慢路径工具只能是 ProposalTool；执行必须落在中控进程")
                .doesNotContain("task-orchestrator");
    }

    /**
     * 快路径主源码不得直连 OpenSearch 索引实现（架构草案阶段 0）。
     *
     * <p>检索走 {@code CandidateSearch} SPI；实现留在 {@code capability-registry}。
     * 测试代码允许引用 index 包做真检索评测夹具。
     */
    @Test
    @DisplayName("intent-fastpath 主代码不 import registry.index")
    void fastpathMainDoesNotImportRegistryIndex() throws IOException {
        Path reactor = locateReactorRoot();
        Path root = moduleDir("intent-fastpath").resolve("src/main/java");
        assertThat(root).as("快路径主源码目录不存在").exists();
        List<String> offenders = new java.util.ArrayList<>();
        try (var files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (text.contains("com.huawei.finance.registry.index")) {
                        offenders.add(reactor.relativize(p).toString());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(offenders)
                .as("快路径主代码只能依赖 CandidateSearch SPI，不得直连 OpenSearch 索引包")
                .isEmpty();
    }

    /**
     * 引擎不把 OpenSearch 拖给使用方（架构草案 §4.3 第 3 行）。
     *
     * <p>资产（{@code com.huawei.finance.registry.asset}）与检索索引（{@code com.huawei.finance.registry.index}，
     * 直连 OpenSearch）此前同住 {@code capability-registry}。于是
     * {@code intent-fastpath} / {@code response-engine} 这些只要读资产的模块，都凭传递依赖
     * 被拖进 OpenSearch 客户端与向量化网关。资产已切到 {@code asset-registry}。
     *
     * <p>{@code intent-fastpath} 仍**在 test 作用域**留着索引侧，这是刻意的：
     * {@code FastPathLiveFixture} 等要连真 OpenSearch 做检索评测。test 作用域不传递，
     * 所以使用方拿不到——本用例断言的正是这个作用域，而不是「不许出现」。
     * 作用域一旦被改成 compile，评测照样能跑、所有功能用例照样绿，
     * 只有依赖它的人多背一个 OpenSearch 客户端。这种退化只能在依赖关系上守。
     */
    @Test
    @DisplayName("intent-fastpath / response-engine 的编译期依赖里没有索引侧")
    void engineDoesNotDragOpenSearchToConsumers() throws IOException {
        String fastpath = pomOf("intent-fastpath");
        assertThat(declaresArtifact(fastpath, "asset-registry"))
                .as("intent-fastpath 该只依赖资产模块")
                .isTrue();
        assertThat(scopeOf(fastpath, "capability-registry"))
                .as("intent-fastpath 若依赖索引侧，只能是 test 作用域——它不传递，"
                        + "使用方才不会被拖进 OpenSearch")
                .isIn("test", "absent");

        String response = pomOf("response-engine");
        assertThat(scopeOf(response, "capability-registry"))
                .as("response-engine 只用 AssetBundle / ClarifyConfig / TemplateDef，不该碰索引侧")
                .isEqualTo("absent");

        String asset = pomOf("asset-registry");
        assertThat(declaresArtifact(asset, "opensearch-java"))
                .as("asset-registry 一旦自己依赖 OpenSearch，这次拆分就白做了")
                .isFalse();
        assertThat(declaresArtifact(asset, "model-openai-compatible"))
                .as("资产的加载与校验不需要向量化网关；它属于索引构建那侧")
                .isFalse();

        assertThat(dependencies.get("asset-registry"))
                .as("依赖方向必须是 registry→asset。反了 OpenSearch 就又成了资产的传递依赖")
                .doesNotContain("capability-registry");
        assertThat(dependencies.get("capability-registry"))
                .as("能力注册 SPI 不应反向拖入产品资产")
                .doesNotContain("asset-registry");
    }

    /**
     * 某个 artifact 在 pom 里的作用域；没声明该依赖时返回 {@code "absent"}，
     * 声明了但没写 {@code <scope>} 时返回 Maven 的默认值 {@code "compile"}。
     *
     * <p>只看当前 {@code <dependency>} 块内的 {@code <scope>}，避免把后面别的依赖的
     * 作用域读到这一条上——那会让一条 compile 依赖被误判成 test，而这条用例恰恰
     * 全靠作用域来区分「可以有」和「不可以有」。
     */
    private static String scopeOf(String pomText, String artifactId) {
        Matcher m = Pattern.compile(
                        "<dependency>(?:(?!</dependency>).)*?<artifactId>\\s*"
                                + Pattern.quote(artifactId) + "\\s*</artifactId>"
                                + "((?:(?!</dependency>).)*?)</dependency>", Pattern.DOTALL)
                .matcher(stripXmlComments(pomText));
        if (!m.find()) {
            return "absent";
        }
        Matcher scope = Pattern.compile("<scope>\\s*([a-z]+)\\s*</scope>").matcher(m.group(1));
        return scope.find() ? scope.group(1) : "compile";
    }

    /**
     * 引擎不依赖 Redis（架构草案 §4.3 第 4 行明确要求钉住这条）。
     *
     * <p>出口缓存是 {@code DecisionCache} 这个 {@code @Spi}，引擎侧只留
     * {@code DecisionCache.disabled()} 作默认值，Redis 基线实现在 {@code cache-redis}。
     *
     * <p>为什么值得一条专门的用例：Redisson 回到引擎的 pom 里不会让任何功能用例变红——
     * 它只是让每个复用引擎的 Agent 都多拖一个 Redis 客户端，包括自带缓存中间件的、
     * 以及按监管要求不得把决策结论落到进程外的。这种退化在功能测试里完全不可见，
     * 只能在依赖关系上守。
     *
     * <p>方向也要守：{@code cache-redis} 依赖引擎，反过来不行。反了就等于绕一圈
     * 把 Redisson 又变成引擎的传递依赖，而绕了一圈之后更不容易看出来。
     */
    @Test
    @DisplayName("intent-fastpath 不依赖 redisson，缓存实现在独立适配模块里")
    void fastpathDoesNotDependOnRedis() throws IOException {
        String enginePom = pomOf("intent-fastpath");
        assertThat(declaresArtifact(enginePom, "redisson"))
                .as("intent-fastpath 的 pom 又出现了 redisson。出口缓存是 @Spi，"
                        + "Redis 实现应留在 cache-redis")
                .isFalse();

        String adapterPom = pomOf("cache-redis");
        assertThat(declaresArtifact(adapterPom, "redisson"))
                .as("cache-redis 该是 Redis 实现的落脚处，它得真的依赖 redisson，"
                        + "否则上面那条断言是空跑")
                .isTrue();

        assertThat(dependencies.get("intent-fastpath"))
                .as("依赖方向必须是适配器→引擎。反过来 Redisson 就又成了引擎的传递依赖")
                .doesNotContain("cache-redis");
        // 此前这里写的是 contains("intent-fastpath")。适配器要的只是 DecisionCache 这个
        // @Spi，为它依赖整个实现模块把 hanlp / aviator / agent-core-java 全背了进来。
        // 门面拆出 intent-engine-api 之后改成依赖门面——方向仍是适配器→契约，
        // 而背的东西少了。「适配器确实还认这个契约」由这条守着，
        // 「它确实不再需要实现」由 intentEngineApiIsDependableAlone 守着。
        assertThat(dependencies.get("cache-redis"))
                .as("cache-redis 得真的认 DecisionCache 所在的模块，否则上面几条是空跑")
                .contains("intent-engine-api");
    }

    /**
     * 意图引擎门面可以单独被依赖（架构草案 §0.3 第 3 条）。
     *
     * <p>§0.3 要求「意图引擎必须单独定义为一级模块，供所有 Agent 引用」。门面类此前是有的，
     * 但住在 {@code intent-fastpath} 里，而那个模块编译期带 hanlp、aviator、agent-core-java。
     * 于是「依赖意图引擎」实际等于「依赖一套具体实现」，§0.3 只在文档里成立。
     *
     * <p>这不是假想的代价，是量出来的：{@code adapters/cache-redis} —— 一个**适配器** ——
     * 只为实现一个 {@code @Spi}（{@code DecisionCache}，只依赖 stability + contracts），
     * 就依赖了 {@code intent-fastpath}，把上面那三个它一个都不用的东西全背进了 classpath。
     *
     * <p>断的是**编译期闭包**而不是直接依赖：门面加一条 {@code asset-registry} 不会让任何
     * 功能用例变红，只会让每个引门面的 Agent 多背一串 jar。这种退化在功能测试里完全不可见。
     *
     * <p>这条同时兜住 {@code RedisDecisionCacheConfiguration} 上那个改成字符串形式的
     * {@code @AutoConfigureBefore}——它证明 cache-redis 真的不再需要 fastpath 在编译期在场，
     * 而不只是「这一处改成了字符串」。
     *
     * <p><b>两条断言的验证程度不一样，记在这里免得下一个人误以为都验过。</b>
     * 直接依赖那条做过变异验证（给门面加一条 {@code asset-registry}，它变红）。
     * 闭包遍历那条**没有**，而且今天用一次 pom 改动碰不到：要触发它，必须让某个地基模块
     * 长出一条指向被禁模块的边，而被禁模块反过来全都依赖地基——量过一遍，九个里七个直接依赖
     * 全部三个地基，剩下 {@code cache-redis} 与 {@code mobile-banking-assistant} 表面只依赖
     * {@code agent-stability}，但前者依赖门面本身，绕一圈还是环。任何这样的边都是 Maven 环路，
     * 在跑到断言之前就先失败了。
     *
     * <p>所以闭包那条守的不是当下可达的退化，是**地基长大之后**的：哪天
     * 某个基础 API 模块添一个新依赖，而那个新依赖自己拖了资产模块，直接依赖那条看不见，
     * 只有闭包这条会响。留着它是因为那一天的代价是每个引门面的 Agent 都多背一串 jar，
     * 而这种退化在功能测试里完全不可见。
     */
    @Test
    @DisplayName("intent-engine-api 的编译期闭包里没有任何实现（§0.3 第 3 条）")
    void intentEngineApiIsDependableAlone() throws IOException {
        // 允许的全部：三个地基模块。门面就该只有它们。
        assertThat(dependencies.get("intent-engine-api"))
                .as("门面只该依赖地基。多一个都会摊派给每个引它的 Agent")
                .containsExactlyInAnyOrder("stability-api", "agent-api", "task-api", "a2a-api");

        // 闭包遍历：地基自己再长出依赖，也一样会摊派过去。
        var closure = new TreeSet<String>();
        var pending = new ArrayDeque<>(dependencies.get("intent-engine-api"));
        while (!pending.isEmpty()) {
            String next = pending.poll();
            if (closure.add(next)) {
                pending.addAll(dependencies.getOrDefault(next, new TreeSet<>()));
            }
        }
        assertThat(closure)
                .as("门面的编译期闭包里出现了实现或资产模块，§0.3 的「供所有 Agent 引用」就又空了")
                .doesNotContain("intent-fastpath", "intent-slowpath",
                        "asset-registry", "capability-registry",
                        "context-engine", "task-orchestrator", "response-engine",
                        "cache-redis", "mobile-banking-assistant");

        // 第三方那侧同样要断：上面看的是在册模块，hanlp 这些不在册，不会被上面拦住。
        String apiPom = pomOf("intent-engine-api");
        for (String heavy : List.of("hanlp", "aviator", "redisson", "opensearch-java",
                "agent-core-java")) {
            assertThat(declaresArtifact(apiPom, heavy))
                    .as("门面不该依赖 %s。别的 Agent 引门面时会连它一起背走", heavy)
                    .isFalse();
        }

        // 反向：实现必须真的依赖门面，否则「门面」只是一组没人实现的接口。
        assertThat(dependencies.get("intent-fastpath"))
                .as("intent-fastpath 是门面的实现，方向必须是实现→门面")
                .contains("intent-engine-api");
        assertThat(dependencies.get("intent-engine-api"))
                .as("门面反过来依赖实现就成环了，也等于白拆")
                .doesNotContain("intent-fastpath");
    }

    /** 按 artifactId 读某模块的 pom 原文（含注释，交给各用例自己决定要不要去）。 */
    private static String pomOf(String artifactId) throws IOException {
        return Files.readString(moduleDir(artifactId).resolve("pom.xml"), StandardCharsets.UTF_8);
    }

    /** pom 里是否声明了某个第三方 artifact（注释里出现不算）。 */
    private static boolean declaresArtifact(String pomText, String artifactId) {
        return Pattern.compile("<artifactId>\\s*" + Pattern.quote(artifactId) + "\\s*</artifactId>")
                .matcher(stripXmlComments(pomText))
                .find();
    }

    /**
     * 去掉 XML 注释再匹配。
     *
     * <p>不去的话这条用例会被自己的解释文字骗过：{@code intent-fastpath/pom.xml} 里那段
     * 「刻意不依赖 redisson」的注释含 {@code redisson} 字样，正则一样能匹配上，
     * 于是「没有依赖」被读成「有依赖」——一条因为注释写得详细而失败的用例。
     */
    private static String stripXmlComments(String xml) {
        return Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(xml).replaceAll("");
    }

    /**
     * {@code ".."} 起头的相对路径字面量，含 {@code Path.of("..", "x")} 这种分段写法。
     *
     * <p>整段不许出现空白，用来把散文摘出去：{@code @DisplayName("../ 越出资产目录的路径被拒")}
     * 这种字面量以 {@code ../} 起头，却不是路径。本工程没有带空格的路径。
     */
    private static final Pattern UPWARD_PATH = Pattern.compile(
            "\"\\.\\./[^\"\\s]*\"|\"\\.\\.\"\\s*,");

    /**
     * 越界校验用例的被测输入：那条用例的全部内容就是「递一个越界路径进去，看它被不被拒」，
     * 写不出越界路径就测不了。逐字面量豁免，而不是豁免整个文件——
     * 按文件名豁免的话，同一文件里真正要读的路径也一起免检了，
     * 而 {@code AssetEditorTest} 里恰好就有过一处 {@code Path.of("..", "assets")}。
     */
    private static final List<String> INPUT_LITERALS = List.of("\"../stolen.yaml\"");

    /**
     * 源码里不出现 {@code ..} 起头的相对路径。
     *
     * <p>那种路径编的是「本模块在 reactor 的第几层」。26 个模块搬进 {@code adapters/}
     * {@code domains/} {@code applications/} 那次，十几处 {@code Path.of("../assets")} 全部差一层，
     * 而它们没有一个是编译期错误：一部分启动期抛「资产目录不存在」，另一部分——
     * {@code MockNavAgent} 与 {@code ConsoleStaticConfiguration}——只记一行日志然后静默降级。
     *
     * <p>正确写法是 {@code AgentAssetLocations.requireAssets()} / {@code requireRepoRoot()}：
     * 从工作目录向上找 {@code assets/manifest.yaml}，与调用方在第几层无关。
     *
     * <p>注释先被摘掉，所以 {@code AgentAssetLocations} 自己的 javadoc 里举这个反例不会误伤。
     */
    @Test
    @DisplayName("源码里没有 .. 起头的相对路径：那是把模块层数编进代码")
    void noSourceHardcodesUpwardRelativePaths() throws IOException {
        Path root = locateReactorRoot();
        List<String> offenders = new ArrayList<>();
        for (String modulePath : modulePaths.values()) {
            Path src = root.resolve(modulePath).resolve("src");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var files = Files.walk(src)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    // 本文件免检，且这是唯一绕不开的一处：下面那张豁免名单要把
                    // 被豁免的字面量原样写出来，写出来就会被自己扫到。
                    // 本文件的路径一律走 locateReactorRoot()，没有可漏的
                    if (p.getFileName().toString().equals("ModuleDependencyTest.java")) {
                        continue;
                    }
                    // 去注释：本工程惯于在注释里写「此前是 ../assets，为什么改掉」，
                    // 一条解释得越清楚的注释越会把这条用例弄红
                    String text = stripJavaComments(Files.readString(p, StandardCharsets.UTF_8));
                    for (String allowed : INPUT_LITERALS) {
                        text = text.replace(allowed, "\"\"");
                    }
                    Matcher m = UPWARD_PATH.matcher(text);
                    if (m.find()) {
                        offenders.add(root.relativize(p) + " → " + m.group());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("用 AgentAssetLocations 定位仓库目录。写 .. 的话模块一搬就断，"
                        + "且失败方式是启动期异常或静默降级，不是编译错误")
                .isEmpty();
    }

    /**
     * 源码里不出现以模块名起头的路径字面量。
     *
     * <p>与上一条是同一个病的两种写法。上一条抓 {@code ".."}（把「本模块在第几层」编进代码），
     * 这条抓 {@code "<模块名>/src/..."}（把「那个模块在哪一层」编进代码）。
     * 上一轮只加了前者，于是模块搬进 {@code platform/} {@code intent-engine/} {@code registry/}
     * {@code runtime/} 那次，后者在两处各失效了一次：
     *
     * <ul>
     *   <li>{@code ModuleDependencyTest.locateReactorRoot()} 的 {@code "agent-bom/pom.xml"}
     *       ——抛异常，本类所有断言一条没跑
     *   <li>{@code ThinEntryBoundaryTest.entrySourceRoots()} 的
     *       {@code "mobile-banking-assistant/src/main/java/com/agent-platform/app"} ——**静默扫空、稳定通过**
     * </ul>
     *
     * <p>后者是这条闸门存在的理由。这类字面量搬目录后不会编译错，路径不存在也往往不抛，
     * 只是让扫描读到零个文件；而扫源码的用例断言的都是「offenders 为空」，
     * 读零个文件恰好通过。也就是说这个病的默认表现是**闸门自己失效而没人知道**。
     *
     * <p>第一段也匹配 artifactId：{@code mobile-banking-assistant} 是 artifactId 而不是目录名
     * （目录是 {@code agents/mobile-banking-assistant}），只按目录名匹配抓不到上面第二处。
     *
     * <p>正确写法是 {@link ReactorLayout#moduleDir} / {@link ReactorLayout#moduleSourceDir}：
     * 从根 pom 的 {@code <module>} 清单查，且查不到就抛。
     */
    @Test
    @DisplayName("源码里没有以模块名起头的路径字面量：那是把别的模块的层数编进代码")
    void noSourceHardcodesModuleDirectoryNames() throws IOException {
        Path root = locateReactorRoot();

        // 名字池：artifactId ∪ 叶子目录名。两者不重合的地方正是漏抓的地方——
        // mobile-banking-assistant 只是 artifactId（目录是 agents/mobile-banking-assistant），
        // 只按目录名匹配就抓不到上面第三处。
        var names = new TreeSet<String>(modulePaths.keySet());
        var groups = new TreeSet<String>();
        for (String path : modulePaths.values()) {
            Path p = Path.of(path);
            names.add(p.getFileName().toString());
            if (p.getNameCount() > 1) {
                groups.add(p.getName(0).toString());
            }
        }
        // 分组目录名（platform / domains / registry / runtime / adapters / applications）
        // 只允许出现在模块名**前面**，不单独构成命中。它们是通用词，会跟资产目录撞：
        // assets/ 下就有一个 domains/tech-domains.yaml，那是资产路径而不是模块路径
        // （AssetLoader 与 TechDomainNodeBijectionTest 各有一处）。
        // 把分组名也当命中的话，这条闸门第一次跑就报三条，其中两条是误伤——
        // 而误伤会让人给闸门加豁免名单，加着加着就没人信它了。
        String group = "(?:" + groups.stream().map(Pattern::quote)
                .collect(Collectors.joining("|")) + ")/";
        Pattern literal = Pattern.compile(
                "\"(?:" + group + ")?(?:"
                        + names.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                        + ")/[^\"\\s]*\"");

        List<String> offenders = new ArrayList<>();
        for (String modulePath : modulePaths.values()) {
            Path src = root.resolve(modulePath).resolve("src");
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (var files = Files.walk(src)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    // 本文件免检：上面那段说明要把两处反例原样写出来才讲得清，写出来就会被自己扫到。
                    if (p.getFileName().toString().equals("ModuleDependencyTest.java")) {
                        continue;
                    }
                    String text = stripJavaComments(
                            Files.readString(p, StandardCharsets.UTF_8));
                    // 收全而不是 find() 一次就走：同一个文件里往往不止一处
                    // （locateBom + 另一处拼路径就是这样），只报第一处的话
                    // 修了再跑又蹦一条，而且看不出剩下那条是哪种形态
                    Matcher m = literal.matcher(text);
                    while (m.find()) {
                        offenders.add(root.relativize(p) + " → " + m.group());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("用 ReactorLayout.moduleDir/moduleSourceDir 查模块目录。"
                        + "写模块名的话目录一搬就指向不存在的路径，"
                        + "而失败方式通常是扫零个文件然后通过——闸门自己关掉了")
                .isEmpty();
    }

    /** 去掉行注释与块注释。字符串里的 {@code //} 会被误伤，本工程的路径字面量里没有。 */
    private static String stripJavaComments(String java) {
        return java.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * 迁移版本号全局唯一。
     *
     * <p>Flyway 把 classpath 上所有 {@code db/migration} 合并成**一条**版本序列。各模块
     * 从 V1 起各编各的，装到一起就是版本撞车：Flyway 只认第一个，另一个模块的建表语句
     * 从此再也不会执行——而它的失败方式是启动照常成功，直到第一次访问那张不存在的表。
     */
    @Test
    @DisplayName("各模块的 Flyway 迁移版本号不撞车")
    void migrationVersionsAreUniqueAcrossModules() throws IOException {
        Path root = locateReactorRoot();
        Map<String, List<String>> byVersion = new TreeMap<>();
        try (var files = Files.walk(root)) {
            files.filter(p -> p.getParent() != null
                            && p.getParent().endsWith(Path.of("db", "migration")))
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(p -> {
                        String version = p.getFileName().toString().split("__")[0];
                        byVersion.computeIfAbsent(version, k -> new java.util.ArrayList<>())
                                .add(root.relativize(p).toString());
                    });
        }

        assertThat(byVersion).as("一个迁移文件都没找到，说明扫描路径错了").isNotEmpty();
        var collisions = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .toList();
        assertThat(collisions)
                .as("同版本号的迁移只有一个会被执行，另一个的建表语句永远不会跑")
                .isEmpty();
    }

    /**
     * reactor 根。
     *
     * <p>这里原先自己向上找「含 {@code agent-bom/pom.xml} 的目录」。agent-bom 搬进
     * {@code platform/} 之后它向上找到文件系统根也没命中，{@code @BeforeAll} 抛异常，
     * 本类 16 条断言一条都没跑。哨兵换成模块目录名之外的东西才不会再犯——
     * 见 {@link ReactorLayout#repoRoot()}。
     */
    private static Path locateReactorRoot() {
        return ReactorLayout.repoRoot();
    }
}
