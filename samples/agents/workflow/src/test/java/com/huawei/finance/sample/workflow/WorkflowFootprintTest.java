package com.huawei.finance.sample.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.tck.ReactorLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 守住依赖足迹。
 *
 * <p>agent-core-java 裸装是 101 个 jar / 162 MB，其中 Milvus SDK、Pulsar client、POI、PDFBox、
 * SQLite、openai-java 我们一个都不用，但它们会进银行的 SBOM 与 CVE 扫描。排除项写在 agent-bom 里，
 * 而排除项这种东西很容易在某次「加个依赖顺手整理一下 pom」时消失，且消失后**一切照常工作**——
 * 只是交付物悄悄胖了 130 MB、多了一批我们没审过的第三方。所以要有一条测试盯着。
 */
class WorkflowFootprintTest {

    /** 必须一直被排除的坐标。改动这个清单需要同步更新 agent-bom 与 README 的说明。 */
    private static final List<String> EXCLUDED_ARTIFACTS = List.of(
            "milvus-sdk-java", "pulsar-client", "pdfbox", "poi-ooxml",
            "sqlite-jdbc", "openai-java", "dashscope-sdk-java", "pgvector", "logback-classic");

    /** 排除后不该出现在 classpath 上的标志类。 */
    private static final List<String> ABSENT_CLASSES = List.of(
            "io.milvus.client.MilvusServiceClient",
            "org.apache.pulsar.client.api.PulsarClient",
            "org.apache.pdfbox.pdmodel.PDDocument",
            "org.apache.poi.ss.usermodel.Workbook",
            "org.sqlite.JDBC",
            "com.openai.client.OpenAIClient");

    @Test
    @DisplayName("agent-bom 里的排除项没有被删掉")
    void bomKeepsExclusions() throws IOException {
        String bom = Files.readString(locateBom());
        int start = bom.indexOf("<artifactId>agent-core-java</artifactId>");
        int end = bom.indexOf("</dependency>", start);
        assertThat(start).as("agent-bom 里应当有 agent-core-java 的版本声明").isNotNegative();

        String declaration = bom.substring(start, end);
        assertThat(EXCLUDED_ARTIFACTS)
                .allSatisfy(artifact -> assertThat(declaration)
                        .as("agent-core-java 的排除项里少了 " + artifact
                                + "，交付物会悄悄多出一批我们没审过的依赖")
                        .contains("<artifactId>" + artifact + "</artifactId>"));
    }

    /**
     * BOM 的位置，从根 pom 的模块清单查。
     *
     * <p>这里改过两轮，值得记下来。第一版写 {@code "../agent-bom/pom.xml"}，本模块搬进
     * {@code domains/} 之后差一层。第二版改成向上逐层找 {@code "agent-bom/pom.xml"}——
     * **治好了写死层数，没治写死模块名**：agent-bom 搬进 {@code platform/} 之后，
     * 向上找到文件系统根也命中不了，于是这一轮照旧断。
     *
     * <p>所以第三版两个都不猜，走 {@link ReactorLayout#moduleDir}：根认
     * {@code assets/manifest.yaml} 这个文件，模块位置查根 pom 的 {@code <module>} 清单。
     * 「别处不许再写模块名字面量」由
     * {@code ModuleDependencyTest#noSourceHardcodesModuleDirectoryNames} 钉住——
     * 上面第二版就是被那条闸门抓出来的。
     */
    private static Path locateBom() throws IOException {
        return ReactorLayout.moduleDir("agent-bom").resolve("pom.xml");
    }

    @Test
    @DisplayName("被排除的依赖确实不在 classpath 上")
    void excludedDependenciesAreReallyGone() {
        for (String className : ABSENT_CLASSES) {
            boolean present = true;
            try {
                Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                present = false;
            }
            assertThat(present)
                    .as(className + " 出现在 classpath 上，说明排除项没生效或被别的依赖又拉回来了")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("工作流引擎本体在，排除没有排过头")
    void engineItselfIsPresent() {
        // 上一条测试若因为把引擎也排掉而变绿，那是最糟的通过方式
        assertThat(com.openjiuwen.core.workflow.Workflow.class).isNotNull();
        assertThat(com.openjiuwen.core.graph.pregel.Pregel.class).isNotNull();
    }
}
