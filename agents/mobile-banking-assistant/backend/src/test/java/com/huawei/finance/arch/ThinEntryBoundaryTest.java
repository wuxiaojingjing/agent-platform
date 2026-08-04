package com.huawei.finance.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.tck.ReactorLayout;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 薄入口禁区（架构草案 v0.3 §2.3、阶段 1.5 门禁、§14「薄入口禁区清单」）。
 *
 * <p>入口 Agent 禁止解析「第几张卡」「转一半」这类**域内指代**。这不是洁癖:
 * 入口一旦自己解，26 个域的语义就会在手机银行助手里各抄一份，然后跟着域各自演进，
 * 直到两边对不上——而对不上的表现是转错卡，不是报错。
 *
 * <p>阶段 1.5 把这段逻辑从 {@code com.huawei.finance.context.WorkingMemoryResolver} 下沉到
 * {@code account}，入口只认 {@code DomainReferenceResolver} 端口。
 * 本用例守的是这条不再回流。
 *
 * <p>为什么必须有这条守卫:{@code mobile-banking-assistant} 对账户域是 {@code runtime} 依赖，
 * 编译期本来就 import 不到 {@code AccountReferenceResolver}。但作用域是一行 pom
 * 就能改回 compile 的，改了之后所有功能用例照样绿——入口重新长出域内语义
 * 不会让任何断言变红，只能在依赖关系上守。
 */
class ThinEntryBoundaryTest {

    /**
     * 入口包前缀。
     *
     * <p>{@code com.huawei.finance.context} 算入口:它是入口侧的上下文编译与租约，
     * 域节点不用它（域侧上下文归域节点自己）。原先那段账户解析就住在这里。
     */
    private static final List<String> ENTRY_PACKAGES = List.of(
            "com.huawei.finance.product.mobilebanking",
            "com.huawei.finance.context");

    /** 域内语义实现所在的包，入口一律不得依赖。 */
    private static final List<String> DOMAIN_IMPL_PACKAGES = List.of(
            "com.huawei.finance.domain.account",
            "com.huawei.finance.domain.transfer",
            "com.huawei.finance.domain.creditcard",
            "com.huawei.finance.domain.wealth",
            "com.huawei.finance.domain.fund",
            "com.huawei.finance.domain.insurance",
            "com.huawei.finance.domain.finance",
            "com.huawei.finance.sample.mock");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.finance");
    }

    @Test
    @DisplayName("入口包不依赖任何域内语义实现（v0.3 §2.3）")
    void entryDoesNotDependOnDomainImplementations() {
        var offenders = new TreeSet<String>();
        for (JavaClass clazz : production) {
            if (ENTRY_PACKAGES.stream().noneMatch(p -> clazz.getPackageName().startsWith(p))) {
                continue;
            }
            for (var dependency : clazz.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getFullName();
                if (DOMAIN_IMPL_PACKAGES.stream().anyMatch(target::startsWith)) {
                    offenders.add(clazz.getName() + " → " + target);
                }
            }
        }

        assertThat(offenders)
                .as("入口只能经 DomainReferenceResolver 端口拿域内解析结果。"
                        + "直接依赖实现等于把域语义又抄回了入口（§2.3 禁止清单）")
                .isEmpty();
    }

    /**
     * 入口源码里不得再出现域内指代的字面量。
     *
     * <p>读源码而不是读字节码，是因为要防的是「自己复制一份正则」这种回流:
     * 那种写法不 import 域模块，上一条依赖守卫看不见它,而它恰恰是最省事、
     * 因此最可能发生的抄法。ArchUnit 也读不到常量值,所以这条只能扫源码。
     */
    @Test
    @DisplayName("入口源码不出现「第几张卡 / 一半」这类域内指代的解析实现")
    void entryDoesNotReimplementOrdinalResolution() throws IOException {
        List<String> markers = List.of("__card", "__half", "张卡", "二分之一");
        // 也扫 import:javac 会把 `static final String` 常量**内联**进调用方字节码,
        // 于是入口写 AccountReferenceResolver.ORDINAL_PREFIX 时,字节码里对该类
        // 一处引用都不留——上一条依赖守卫看不见,标记字面量也不出现在入口源码里。
        // 这个洞是做变异验证时发现的:当时那次变异两条断言都没拦住。
        List<String> forbiddenImports = List.of("import com.huawei.finance.agent.");
        var offenders = new TreeSet<String>();
        int scanned = 0;

        for (Path dir : entrySourceRoots()) {
            try (var files = Files.walk(dir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanned++;
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (String marker : markers) {
                        if (text.contains(marker)) {
                            offenders.add(file.getFileName() + " 含 " + marker);
                        }
                    }
                    for (String forbidden : forbiddenImports) {
                        if (text.contains(forbidden)) {
                            offenders.add(file.getFileName() + " import 了域实现包 " + forbidden);
                        }
                    }
                }
            }
        }

        // 先断「真读到了源码」，再断「读到的里面没有违规」。顺序要紧:
        // 这条用例此前长期扫零个文件然后通过（见 entrySourceRoots 的说明），
        // 而下面那句 isEmpty() 对空扫描是完全满足的。
        assertThat(scanned)
                .as("一个入口源文件都没扫到，说明路径定位错了。"
                        + "这条断言不是冗余的:本用例正是这样假绿过一整轮")
                .isGreaterThan(10);

        assertThat(offenders)
                .as("入口重新长出域内指代解析,不会让任何功能用例变红——只能在这里守")
                .isEmpty();
    }

    /**
     * 入口两个模块的主源码根。
     *
     * <p>这里原先拼的是 {@code reactor.resolve("mobile-banking-assistant/src/main/java/com/agent-platform/app")}，
     * 并靠 {@code getFileName().equals("mobile-banking-assistant")} 判断要不要回退父目录。
     * 两处都不成立:{@code mobile-banking-assistant} 是 artifactId，模块目录是
     * {@code agents/mobile-banking-assistant}。于是不回退父目录，拼出的两个路径都不存在，
     * 而调用方那里写着 {@code if (!Files.isDirectory(dir)) continue;}——
     * **这条守着阶段 1.5 门禁的用例读零个文件、稳定通过**。那段时间往入口写回一个
     * 「第几张卡」的解析实现，没有任何用例会红。
     *
     * <p>所以改成两条都不猜:根走 {@link ReactorLayout#repoRoot()}（认
     * {@code assets/manifest.yaml}，与工作目录在第几层无关），模块目录从根 pom 的
     * {@code <module>} 清单查，且**包不存在直接抛**（{@code moduleSourceDir} 的行为）。
     * 目录缺失必须响:静默跳过正是上面那次假绿的直接机制。
     * 「别处不许再这么写」由 {@code ModuleDependencyTest#noSourceHardcodesModuleDirectoryNames} 钉住。
     */
    private static List<Path> entrySourceRoots() throws IOException {
        return List.of(
                ReactorLayout.moduleSourceDir("mobile-banking-assistant", "com.huawei.finance.product.mobilebanking"),
                ReactorLayout.moduleSourceDir("context-engine", "com.huawei.finance.context"));
    }
}
