package com.huawei.finance.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DeepAgent harness 边界（ADR-004）。
 *
 * <p>慢路径已采用较完整 {@code DeepAgent}。张力 C1–C5 写在 ADR 与 README，不靠关掉
 * taskLoop 假装没有。本守卫改为：
 * <ul>
 *   <li>{@code com.huawei.finance.slowpath} 可以依赖 deep_agent / workspace / task_loop；</li>
 *   <li>其它生产包仍不得扩散这些依赖；</li>
 *   <li>subagents / lsp / cli 仍禁（能力面不得脱离能力卡）；</li>
 *   <li>任何地方不得打开 sysOperation。</li>
 * </ul>
 */
class DeepAgentBoundaryTest {

    /** 仅 slowpath 允许的 harness 包前缀。 */
    private static final List<String> SLOWPATH_ALLOWED = List.of(
            "com.openjiuwen.harness.deep_agent",
            "com.openjiuwen.harness.workspace",
            "com.openjiuwen.harness.task_loop",
            "com.openjiuwen.harness.schema",
            "com.openjiuwen.harness.rails",
            "com.openjiuwen.harness.tools",
            "com.openjiuwen.harness.security",
            "com.openjiuwen.harness.factory",
            "com.openjiuwen.deepagents");

    /** 任何生产代码都不得依赖。 */
    private static final List<String> FORBIDDEN_EVERYWHERE = List.of(
            "com.openjiuwen.harness.subagents",
            "com.openjiuwen.harness.lsp",
            "com.openjiuwen.harness.cli");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.huawei.finance");
    }

    @Test
    @DisplayName("除 slowpath 外，生产代码不扩散 DeepAgent / Workspace / task_loop")
    void onlySlowpathMayDependOnDeepAgentHarness() {
        var offenders = new TreeSet<String>();
        for (JavaClass clazz : production) {
            if (clazz.getPackageName().startsWith("com.huawei.finance.slowpath")) {
                continue;
            }
            for (JavaClass target : clazz.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass()).toList()) {
                String name = target.getFullName();
                if (SLOWPATH_ALLOWED.stream().anyMatch(name::startsWith)
                        || name.startsWith("com.openjiuwen.harness.deep_agent")
                        || name.startsWith("com.openjiuwen.harness.workspace")
                        || name.startsWith("com.openjiuwen.harness.task_loop")) {
                    offenders.add(clazz.getName() + " → " + name);
                }
            }
        }

        assertThat(offenders)
                .as("DeepAgent harness 仅允许 intent-slowpath 接入（ADR-004）；扩散等于第二执行面")
                .isEmpty();
    }

    @Test
    @DisplayName("生产代码不碰 browser/LSP/CLI 子 Agent 包")
    void productionStaysOutOfBrowserLspCli() {
        var offenders = new TreeSet<String>();
        for (JavaClass clazz : production) {
            for (JavaClass target : clazz.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass()).toList()) {
                String name = target.getFullName();
                if (FORBIDDEN_EVERYWHERE.stream().anyMatch(name::startsWith)) {
                    offenders.add(clazz.getName() + " → " + name);
                }
            }
        }

        assertThat(offenders)
                .as("通用浏览器/LSP/CLI 会让「能做什么」脱离能力卡（冲突 C4）")
                .isEmpty();
    }

    @Test
    @DisplayName("没有任何地方打开 OJ 的系统操作能力")
    void noSystemOperationIsEnabled() {
        var offenders = new TreeSet<String>();
        for (JavaClass clazz : production) {
            clazz.getMethodCallsFromSelf().stream()
                    .filter(call -> call.getTarget().getName().toLowerCase().contains("sysoperation"))
                    .forEach(call -> offenders.add(clazz.getName() + " → " + call.getTarget().getName()));
        }

        assertThat(offenders)
                .as("模型生成的计划不该有执行系统命令的通道")
                .isEmpty();
    }
}
