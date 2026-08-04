package com.huawei.finance.tck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * reactor 的模块清单与目录定位，供各模块的架构用例共用。
 *
 * <p>单独成类而不是各用例自己写一份，是因为「某模块在哪一层」这件事被证明会**静默**过期，
 * 而三份实现只会有一份被修。把模块搬进 {@code platform/} {@code intent-engine/}
 * {@code registry/} {@code runtime/} 那次，同一个假设在**三个模块里各失效了一次**，
 * 三种失败方式，严重程度递增：
 *
 * <ul>
 *   <li>{@code ModuleDependencyTest.locateReactorRoot()} 找「含 {@code agent-bom/pom.xml}
 *       的目录」。agent-bom 进了 {@code platform/}，向上找到文件系统根也没命中，
 *       {@code @BeforeAll} 抛异常——整个类的断言一条没跑。**这是好的失败方式**：
 *       响，且响在看得见的地方。
 *   <li>{@code WorkflowFootprintTest.locateBom()} 同一个哨兵、同样抛异常。它上一轮刚从
 *       {@code "../agent-bom/pom.xml"} 改成向上遍历——**修掉了写死层数，没修掉写死模块名**，
 *       于是这一轮照旧断。一个病治了一半会再犯，这是证据。
 *   <li>{@code ThinEntryBoundaryTest.entrySourceRoots()} 拼
 *       {@code "mobile-banking-assistant/src/main/java/com/agent-platform/app"}（{@code mobile-banking-assistant} 还是
 *       artifactId，真目录是 {@code agents/mobile-banking-assistant}）。它没抛，
 *       因为扫描循环开头写着 {@code if (!Files.isDirectory(dir)) continue;}——
 *       **目录不存在被当成「没什么要扫的」**。该用例断言「offenders 为空」，
 *       于是读零个文件、稳定通过。它守的是阶段 1.5 的门禁：那段时间往入口写回一个
 *       「第几张卡」的解析实现，没有任何用例会红。
 * </ul>
 *
 * <p>所以这里定两条规矩：测试根目录只认 Maven Reactor 的显式根或根 pom 标识，
 * 模块目录只从根 pom 的 {@code <module>} 清单查。
 * 两者都不含「模块在第几层」这个知识，且查不到一律抛——静默跳过正是第三种失效的机制本身。
 *
 * <p>放在 {@code agent-tck} 而不是各模块的测试目录：上面三处分属三个模块
 * （{@code agents/mobile-banking-assistant/backend}、{@code samples/agents/workflow}），没有共同的测试源码根，
 * 而 {@code agent-tck} 是它们都可以引入的测试契约包。这类 Reactor 元数据不应进入任何生产 API。
 *
 * <p>「别处不许再自己写一份」由
 * {@code ModuleDependencyTest#noSourceHardcodesModuleDirectoryNames} 钉住。
 */
public final class ReactorLayout {

    private static final Pattern ARTIFACT = Pattern.compile(
            "<artifactId>\\s*([A-Za-z0-9._-]+)\\s*</artifactId>");
    private static final Pattern MODULE = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    /** artifactId → 模块相对 reactor 根的路径，如 {@code framework/contracts/agent-api}。 */
    private static Map<String, String> modulePaths;

    private ReactorLayout() {
    }

    /**
     * reactor 根。
     *
     * <p>优先使用 Maven 注入的 Reactor 根；IDE 单测才向上寻找同时包含根 pom、framework、agents
     * 的目录。该逻辑只存在于 TCK，不会进入生产资产定位。
     */
    public static Path repoRoot() {
        String reactor = System.getProperty("maven.multiModuleProjectDirectory");
        if (reactor != null && !reactor.isBlank()) {
            Path root = Path.of(reactor).toAbsolutePath().normalize();
            if (isReactorRoot(root)) {
                return root;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (isReactorRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 agent-platform Reactor 根");
    }

    private static boolean isReactorRoot(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("framework"))
                && Files.isDirectory(path.resolve("agents"));
    }

    /** artifactId → 相对路径。清单空了直接抛：空表会让所有「offenders 为空」的断言变绿。 */
    public static synchronized Map<String, String> modulePaths() throws IOException {
        if (modulePaths != null) {
            return modulePaths;
        }
        Path root = repoRoot();

        // 模块清单取自根 pom 的 <module>，而不是「列一层子目录 + 名字前缀」。
        // 前缀那种写法有两个失效方式，且都是静默的：模块改名后一个都匹配不上，
        // 模块移进 adapters/ 这类子目录后列不到——两种情况下这张表都会是空的，
        // 而依赖它的断言几乎全是「offenders 为空」，空表恰好让它们全部变绿。
        String rootPom = stripXmlComments(
                Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8));
        List<String> paths = new ArrayList<>();
        Matcher mm = MODULE.matcher(rootPom);
        while (mm.find()) {
            paths.add(mm.group(1));
        }
        if (paths.isEmpty()) {
            throw new IllegalStateException(
                    "根 pom（" + root.resolve("pom.xml") + "）里一个 <module> 都没读到，说明解析错了");
        }

        Map<String, String> resolved = new TreeMap<>();
        for (String path : paths) {
            Path pom = root.resolve(path).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                throw new IllegalStateException("根 pom 列了 " + path + "，但 " + pom + " 不存在");
            }
            resolved.put(
                    selfArtifactId(stripXmlComments(Files.readString(pom, StandardCharsets.UTF_8))),
                    path);
        }
        modulePaths = Map.copyOf(resolved);
        return modulePaths;
    }

    /** 模块目录。路径写死会在下次搬目录时变成假绿，所以只走这里。 */
    public static Path moduleDir(String artifactId) throws IOException {
        String path = modulePaths().get(artifactId);
        if (path == null) {
            throw new IllegalStateException(
                    "模块 " + artifactId + " 不在根 pom 的 <module> 清单里；在册的有 "
                            + modulePaths().keySet());
        }
        return repoRoot().resolve(path);
    }

    /**
     * 模块主源码里某个包的目录，如 {@code moduleSourceDir("mobile-banking-assistant", "com.huawei.finance.product.mobilebanking")}。
     *
     * <p>不存在就抛，不返回 {@link java.util.Optional} 也不返回一个不存在的路径:
     * 调用方拿到不存在的目录之后统统是「扫零个文件所以通过」，也就是上面第二类失效本身。
     */
    public static Path moduleSourceDir(String artifactId, String packageName) throws IOException {
        Path dir = moduleDir(artifactId)
                .resolve("src/main/java")
                .resolve(packageName.replace('.', '/'));
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "模块 " + artifactId + " 里找不到包 " + packageName + "（" + dir + "）。"
                            + "包被改名或搬走了，就在这里改——别让扫描静默跳过，"
                            + "那等于把这条闸门关掉");
        }
        return dir;
    }

    /** 模块自己的 artifactId：先摘掉 {@code <parent>} 块，否则读到的是父工程的。 */
    private static String selfArtifactId(String pomText) {
        Matcher m = ARTIFACT.matcher(pomText.replaceAll("(?s)<parent>.*?</parent>", ""));
        if (!m.find()) {
            throw new IllegalStateException("pom 里找不到自己的 artifactId");
        }
        return m.group(1);
    }

    /**
     * 去掉 XML 注释再匹配。
     *
     * <p>不去的话解析会被 pom 自己的解释文字骗过：本工程的 pom 惯于用注释写明
     * 「刻意不依赖某模块」，而那些解释里就带着模块名与 artifactId。
     */
    public static String stripXmlComments(String xml) {
        return Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(xml).replaceAll("");
    }
}
