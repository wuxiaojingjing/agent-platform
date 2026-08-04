package com.huawei.finance.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.stability.Api;
import com.huawei.finance.stability.Spi;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.core.domain.JavaWildcardType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对外承诺面的边界守卫。
 *
 * <p>基线要交给多家银行二次开发，最常见的失败不是功能不够，而是行内依赖了不该依赖的东西：
 * 二开时随手 import 了一个实现类，基线升级把它改了，行内那套跟着崩，然后基线被迫为一个
 * 从没承诺过的类型背兼容责任。久了基线就动不了了。
 *
 * <p>{@code @Api} / {@code @Spi} 标出了那条线，但**光标注不算约束**——标注只是注释，
 * 会漂移。真正的约束是下面这几条能让构建变红的规则。
 *
 * <p>放在 {@code mobile-banking-assistant} 的测试源码里，是因为它的 classpath 传递覆盖全部库模块，
 * 是唯一能一次看全的位置——反过来说，新增模块若不进这条 classpath，这套规则对它就是空转。
 * 这些用例不启动 Spring 上下文，不需要 Redis / Postgres。
 */
class StabilityBoundaryTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        // 不加 DO_NOT_INCLUDE_JARS：兄弟模块在 reactor 里是目录、发布后是 jar，
        // 排掉 jar 会让这套规则在打包构建里退化成只看 mobile-banking-assistant 一个模块，即空转
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.finance");
    }

    /**
     * 头号规则：承诺稳定的签名里不许漏出没标的类型。
     *
     * <p>违反这条时，承诺是**兑现不了**的：行内为了调用这个方法，必然要 import 那个内部类型，
     * 于是「只依赖 @Api」这句话在物理上做不到。而基线这边看不出问题——本仓库内改内部类型
     * 不会有任何用例变红，破的是仓库外面那些看不见的实现。
     *
     * <p>连记录组件（record 的自动访问器）一起算。它们是真实的公开方法，
     * 行内拿到一个 {@code UnifiedTask} 就能调，把它们排除掉等于把这条规则做成摆设。
     */
    @Test
    @DisplayName("@Api / @Spi 的公开签名里不出现未标注的 agent-platform 类型")
    void promisedSurfaceDoesNotLeakUnmarkedTypes() {
        Set<String> leaks = new TreeSet<>();
        for (JavaClass promised : production) {
            if (!isPromised(promised)) {
                continue;
            }
            for (JavaMethod method : promised.getMethods()) {
                if (!method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
                    continue;
                }
                Set<JavaClass> referenced = new LinkedHashSet<>();
                collect(method.getReturnType(), referenced);
                method.getParameterTypes().forEach(t -> collect(t, referenced));
                for (JavaClass type : referenced) {
                    JavaClass element = unwrapArray(type);
                    if (!element.getPackageName().startsWith("com.huawei.finance")) {
                        continue;
                    }
                    if (isPromised(element) || isNestedIn(element, promised)) {
                        continue;
                    }
                    leaks.add(promised.getSimpleName() + "#" + method.getName()
                            + " 漏出 " + element.getName());
                }
            }
        }
        assertThat(leaks)
                .as("这些方法在承诺稳定的类型上，却引用了没标 @Api/@Spi 的类型。"
                        + "要么把被引用的类型标上（等于把它纳入承诺），"
                        + "要么把它从公开签名里拿掉")
                .isEmpty();
    }

    /**
     * {@code @Spi} 必须是接口。
     *
     * <p>抽象类会把继承结构也变成承诺的一部分：构造器签名、受保护字段、模板方法的调用顺序。
     * 那些远比一组方法签名难保持稳定，而破坏起来毫无征兆——基线改一下模板方法的调用时机，
     * 行内的子类就悄悄换了行为。
     */
    @Test
    @DisplayName("@Spi 只能标在接口上")
    void spisAreInterfaces() {
        classes().that().areAnnotatedWith(Spi.class)
                .should().beInterfaces()
                .because("抽象类会把继承结构一并变成承诺，那比方法签名难稳定得多")
                .check(production);
    }

    /**
     * 每个 {@code @Spi} 都得有让位装配，否则行内实现了也装不进去。
     *
     * <p>这条守的是「标注与装配脱节」：{@code @Spi} 说「你可以实现」，
     * 而实际能不能替换取决于基线那个 {@code @Bean} 有没有
     * {@code @ConditionalOnMissingBean}。两处分别在不同文件里，很容易只改一处——
     * 加了新扩展点却忘了让位条件时，行内的实现会被静默忽略。
     */
    @Test
    @DisplayName("每个 @Spi 都有带 @ConditionalOnMissingBean 的让位装配")
    void everySpiHasAnOverridableBean() {
        Set<String> spiNames = new TreeSet<>();
        for (JavaClass c : production) {
            if (c.isAnnotatedWith(Spi.class)) {
                spiNames.add(c.getName());
            }
        }
        assertThat(spiNames).as("SPI 一个都没找到，说明导入范围或标注出了问题").isNotEmpty();

        Set<String> overridable = new TreeSet<>();
        for (JavaClass c : production) {
            for (JavaMethod m : c.getMethods()) {
                boolean isBean = hasAnnotation(m, "org.springframework.context.annotation.Bean");
                boolean letsWayIfPresent = hasAnnotation(m,
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean")
                        || hasAnnotation(c,
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean");
                if (isBean && letsWayIfPresent) {
                    overridable.add(unwrapArray(m.getRawReturnType()).getName());
                }
            }
        }

        // 三个例外都属于「多实例扩展点」：基线不提供要让位的单例默认实现，
        // 行内注册自己的 Bean 就已经生效，不存在「实现了却被静默忽略」这个风险。
        // DomainAgent：Mock 由 huawei.finance.sample.mock.enabled 显式开关控制，默认关。
        // TechDomainAgent：DomainAgent 的域身份子接口，26 家各注册自己那个，同样是共存关系。
        //   「顶替掉账户域 Agent」不是要支持的语义——真要接管某个域，是注册自己的实现顶掉那一个，
        //   而不是顶掉这个接口的全部实现。
        // DomainOperation：按 List 注入，几家领域各注册若干个叶子操作，本就该共存而不是互相顶替。
        // AgentNode：1+26 个节点按 agentId 进 A2A 路由表（v0.3 §6），
        //   同样是共存关系——让位装配在这里没有意义，「顶替掉账户节点」不是要支持的语义。
        // DomainCapabilityExecutor：每个域给一个，装进自己那个 DomainAgentNode。
        //   26 家各一份，和 DomainOperation 一样是共存而不是互相顶替。
        Set<String> needing = new TreeSet<>(spiNames);
        needing.remove("com.huawei.finance.contracts.port.DomainAgent");
        needing.remove("com.huawei.finance.contracts.port.TechDomainAgent");
        needing.remove("com.huawei.finance.sample.workflow.DomainOperation");
        needing.remove("com.huawei.finance.contracts.a2a.AgentNode");
        needing.remove("com.huawei.finance.a2a.node.DomainCapabilityExecutor");
        needing.remove("com.huawei.finance.intent.extension.CandidatePostProcessor");
        needing.remove("com.huawei.finance.runtime.extension.ResponseEnricher");
        // CapabilityDelegator 是可选的单实现通道，平台不提供伪默认实现；
        // OrchestratorConfiguration 直接消费使用方注册的 Bean。
        needing.remove("com.huawei.finance.contracts.port.CapabilityDelegator");
        // 出口缓存同样是部署可选项：FastPath 通过 ObjectProvider 消费 DecisionCache，
        // 未配置时使用进程内 disabled 实例；控制面只随 Redis 实现出现。
        needing.remove("com.huawei.finance.intent.cache.DecisionCache");
        needing.remove("com.huawei.finance.intent.cache.DecisionCacheControl");
        needing.removeAll(overridable);
        assertThat(needing)
                .as("这些 SPI 标了「行内可实现」，但基线没有给它们留让位装配——"
                        + "行内实现了也装不进去，且是静默失效")
                .isEmpty();
    }

    /**
     * 源码层面：除示范应用外，谁都不许 import Mock。
     *
     * <p>注意这条**管不到 pom**。ArchUnit 读的是字节码依赖，而 {@code task-orchestrator}
     * 此前对 {@code mock} 的编译期依赖恰恰是「pom 里声明了、源码一处没用」
     * ——字节码里没有痕迹，这条规则照样绿。那种污染由
     * {@link ModuleDependencyTest} 直接读 pom 来守。
     */
    @Test
    @DisplayName("除示范应用外，没有源码依赖 Mock 领域 Agent")
    void onlyTheSampleAppDependsOnMockAgents() {
        classes().that().resideOutsideOfPackages("com.huawei.finance.sample.mock..", "com.huawei.finance.product.mobilebanking..")
                .should().onlyDependOnClassesThat().resideOutsideOfPackage("com.huawei.finance.sample.mock..")
                .because("Mock 返回写死的假数据，只该出现在示范应用里")
                .check(production);
    }

    /** 已文档化扩展点必须都在承诺面上。漏标一个，行内就不知道它可以换。 */
    @Test
    @DisplayName("已文档化的扩展点都标了 @Spi")
    void documentedExtensionPointsAreMarked() {
        List<String> expected = List.of(
                "com.huawei.finance.contracts.port.DomainAgent",
                "com.huawei.finance.contracts.port.GuardrailHook",
                "com.huawei.finance.gateway.ModelGatewayClient",
                "com.huawei.finance.intent.cache.DecisionCache",
                "com.huawei.finance.intent.IntentEngineFactory",
                "com.huawei.finance.intent.extension.CandidatePostProcessor",
                "com.huawei.finance.runtime.extension.ResponseEnricher",
                "com.huawei.finance.obs.trace.DecisionTracePolicy",
                "com.huawei.finance.obs.trace.DecisionTrace",
                // 声明式办理流程的叶子操作。列在这里还有一个附带作用：它证明
                // workflow 确实在本测试的导入范围内，
                // 否则上面那条 @Spi 让位规则对该模块只是空转
                "com.huawei.finance.sample.workflow.DomainOperation");
        Set<String> marked = new TreeSet<>();
        for (JavaClass c : production) {
            if (c.isAnnotatedWith(Spi.class)) {
                marked.add(c.getName());
            }
        }
        assertThat(marked).containsAll(expected);
    }

    /**
     * 连泛型实参一起收。
     *
     * <p>只看 {@code getRawParameterTypes} 会漏掉一整类泄漏：{@code List<ScoredCandidate>}
     * 擦除后是 {@code List}，而行内实现这个方法时绕不开 {@code ScoredCandidate}。
     * 那正是最需要被承诺的类型，却恰好是原始签名看不见的。
     */
    private static void collect(JavaType type, Set<JavaClass> into) {
        if (type instanceof JavaParameterizedType parameterized) {
            parameterized.getActualTypeArguments().forEach(arg -> collect(arg, into));
        }
        if (type instanceof JavaWildcardType wildcard) {
            wildcard.getUpperBounds().forEach(bound -> collect(bound, into));
            wildcard.getLowerBounds().forEach(bound -> collect(bound, into));
            return;
        }
        if (type instanceof JavaTypeVariable<?> variable) {
            variable.getUpperBounds().forEach(bound -> collect(bound, into));
            return;
        }
        into.add(type.toErasure());
    }

    private static boolean isPromised(JavaClass type) {
        JavaClass outermost = type;
        while (outermost.getEnclosingClass().isPresent()) {
            outermost = outermost.getEnclosingClass().get();
        }
        return type.isAnnotatedWith(Api.class) || type.isAnnotatedWith(Spi.class)
                || outermost.isAnnotatedWith(Api.class) || outermost.isAnnotatedWith(Spi.class);
    }

    /** 嵌套在承诺类型内部的类型随外层一起被承诺，不必单独标。 */
    private static boolean isNestedIn(JavaClass candidate, JavaClass outer) {
        return candidate.getName().startsWith(outer.getName() + "$");
    }

    private static JavaClass unwrapArray(JavaClass type) {
        JavaClass current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        return current;
    }

    private static boolean hasAnnotation(JavaMethod method, String annotationName) {
        return method.getAnnotations().stream()
                .anyMatch(a -> a.getRawType().getName().equals(annotationName));
    }

    private static boolean hasAnnotation(JavaClass type, String annotationName) {
        return type.getAnnotations().stream()
                .anyMatch(a -> a.getRawType().getName().equals(annotationName));
    }
}
