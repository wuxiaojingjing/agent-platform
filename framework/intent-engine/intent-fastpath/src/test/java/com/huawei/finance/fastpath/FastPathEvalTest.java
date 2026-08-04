package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.fastpath.eval.EvalCase;
import com.huawei.finance.fastpath.eval.EvalReport;
import com.huawei.finance.fastpath.eval.EvalSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 快路径评测（FP-52）。
 *
 * <p>做成 JUnit 用例而不是一个独立的离线脚本，理由是**只有跟着每次构建一起跑的东西才不会腐烂**。
 * 单独的评测脚本的下场通常是：第一个月每周跑，第三个月没人记得它需要哪个参数。
 *
 * <p>它与既有用例的分工：{@link FourExitsTest} 那类断言的是**单条语义为什么该这样**，
 * 读它能学到规则；这里断言的是**一批语料整体没有变**，读它只能知道变没变。
 * 两者都要有——前者少了，改动无从判断对错；后者少了，改动的影响面无从知晓。
 */
class FastPathEvalTest {

    private static final EvalSet SET = EvalSet.load();
    private static final EvalReport REPORT = new EvalReport();

    static Stream<EvalCase> cases() {
        return SET.getCases().stream();
    }

    @AfterAll
    static void printReport() {
        // 打在最后而不是断言它：报告是给人读的，把它变成断言等于要求人先猜出一个数字
        System.out.println(REPORT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("种子集逐条比对")
    void matchesExpectation(EvalCase testCase) {
        FastPathEvalRunner.Actual actual = FastPathEvalRunner.run(testCase);
        List<String> diffs = FastPathEvalRunner.diff(testCase, actual);
        REPORT.record(testCase, actual.decision(), diffs.isEmpty());

        // known-gap 也断言：现状不对但已被记录，它再变一次同样要有人知道。
        // 只是它在报告里不计入通过率——跑出了预期中的错误结果叫「缺口还在」，不叫「通过」
        assertThat(diffs)
                .withFailMessage("%s（%s）\n  期望之外的差异：%s\n  实测：%s\n  用例说明：%s",
                        testCase.getId(), testCase.getStatus(), diffs,
                        FastPathEvalRunner.asYamlExpect(actual), testCase.getNote())
                .isEmpty();
    }

    @Nested
    @DisplayName("稳定性：同输入重复跑必须逐位一致")
    class Stability {

        /**
         * 同一条输入用三套全新装配各跑一次。
         *
         * <p>验的是**引擎自身的确定性**，不是模型的确定性——网关在这里是不可用替身，
         * 走的是规则仲裁。这个区分很要紧：真正的模型稳定性要接实网关跑，
         * 那属于 {@code ModelGatewayLiveTest} 那一层，无密钥时整类跳过。
         * 而引擎侧的不确定性来源另有其人：HashMap 迭代顺序、依赖真实时钟、
         * 并发下的共享可变状态——这些一旦漏进来，症状是「偶发」，最难查。
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.huawei.finance.fastpath.FastPathEvalTest#cases")
        void repeatedRunsAgree(EvalCase testCase) {
            FastPathEvalRunner.Actual first = FastPathEvalRunner.run(testCase);
            for (int i = 0; i < 2; i++) {
                assertThat(FastPathEvalRunner.run(testCase))
                        .as("第 %d 次重跑与首次不一致：%s", i + 2, testCase.getId())
                        .isEqualTo(first);
            }
        }
    }

    @Nested
    @DisplayName("种子集自身的体检")
    class DatasetHealth {

        @Test
        @DisplayName("id 唯一：重复 id 会让报告里两条用例互相覆盖")
        void idsAreUnique() {
            Set<String> seen = new HashSet<>();
            List<String> dups = SET.getCases().stream()
                    .map(EvalCase::getId)
                    .filter(id -> !seen.add(id))
                    .toList();
            assertThat(dups).isEmpty();
        }

        @Test
        @DisplayName("每条都有 id 与 query")
        void casesAreWellFormed() {
            for (EvalCase c : SET.getCases()) {
                assertThat(c.getId()).isNotBlank();
                assertThat(c.getQuery()).as("用例 %s 的 query", c.getId()).isNotBlank();
            }
        }

        @Test
        @DisplayName("期望里写到的能力必须真实存在，否则用例永远红且看不出原因")
        void referencedCapabilitiesExist() {
            var bundle = FastPathFixture.assets();
            for (EvalCase c : SET.getCases()) {
                String capability = c.getExpect().getCapability();
                if (capability != null) {
                    assertThat(bundle.capability(capability))
                            .as("用例 %s 引用了不存在的能力 %s", c.getId(), capability)
                            .isNotNull();
                }
            }
        }

        @Test
        @DisplayName("种子集覆盖当前离线入口基线出口")
        void baselineEntryExitsAreCovered() {
            Set<String> covered = SET.getCases().stream()
                    .map(c -> c.getExpect().getDecision())
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            for (Decision decision : List.of(Decision.EXECUTE_CAPABILITY, Decision.CLARIFY,
                    Decision.STATIC_PLAN, Decision.HANDOFF)) {
                assertThat(covered).as("出口 %s 无用例覆盖", decision).contains(decision.name());
            }
        }

        @Test
        @DisplayName("known-gap 必须写清缺口是什么")
        void knownGapsAreExplained() {
            for (EvalCase c : SET.getCases()) {
                if (c.knownGap()) {
                    assertThat(c.getNote())
                            .as("用例 %s 标了 known-gap 却没说缺口是什么——日后没人分得清它是待修还是已认可",
                                    c.getId())
                            .isNotBlank();
                }
            }
        }

        @Test
        @DisplayName("业务签署的用例不得同时标 known-gap：那两件事互相矛盾")
        void businessLabelsAreNotGaps() {
            for (EvalCase c : SET.getCases()) {
                if (EvalCase.LabelSource.BUSINESS.equals(c.getLabeledBy())) {
                    assertThat(c.knownGap())
                            .as("用例 %s 既称业务签署真值，又称现状是缺口", c.getId())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("版本号必须显式写出：没有版本的评测集，跨次比对无从对齐")
        void versionIsDeclared() {
            assertThat(SET.getVersion()).startsWith("eval-seed-v");
        }
    }
}
