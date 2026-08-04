package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.fastpath.recall.MultiTaskDetector;
import com.huawei.finance.registry.asset.FusionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 多任务检测的正例与负例。
 *
 * <p>负例集是这组用例存在的主要理由。只验正例的多任务检测一定会演化成宁可错杀：
 * 往条件词表里多塞几个词，正例全绿，而「如果我卡丢了怎么办」这类单一咨询被悄悄推进慢路径，
 * 没有任何用例会红。
 */
class MultiTaskDetectorTest {

    private static final MultiTaskDetector DETECTOR =
            new MultiTaskDetector(FastPathFixture.assets().fusion().getMultiTask());

    private static final int TWO_CAPABILITIES = 2;
    private static final int ONE_CAPABILITY = 1;

    @Nested
    @DisplayName("召回可信时，词表信号必须有召回证据佐证")
    class RecallTrustworthy {

        @ParameterizedTest
        @ValueSource(strings = {
                "如果我卡丢了怎么办",
                "要是余额不够会怎样",
                "密码输错的话就会锁卡吗",
                "如果我要换卡需要什么材料",
                "转账失败了如果钱扣了怎么办",
                "要是我忘记密码了呢",
                "信用卡逾期的话就会上征信吗",
                "如果额度不够能提额吗",
                "否则我该找谁",
                "我再查一下余额",
                "顺便看看我的账单",
                "然后呢",
                "同时支持哪些银行",
                "另外我想问下手续费",
                "接着上次那个问题",
        })
        @DisplayName("单一意图里出现条件词或连词，不判多任务")
        void singleIntentWithLexicalSignalIsNotMultiTask(String query) {
            MultiTaskDetector.Signal signal = DETECTOR.detect(query, ONE_CAPABILITY, true);

            assertThat(signal.multiTask())
                    .as("「%s」只表达了一件事，判成多任务会让用户看到「请逐项办理」", query)
                    .isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "查余额，再给老徐转 1000；不足就别转",
                "先看看账单，然后帮我还款",
                "查一下余额，顺便把这个月的账单也调出来",
                "如果余额够就给张三转 500",
        })
        @DisplayName("确实有两件事且召回到两个能力，判多任务")
        void genuineMultiTaskIsDetected(String query) {
            MultiTaskDetector.Signal signal = DETECTOR.detect(query, TWO_CAPABILITIES, true);

            assertThat(signal.multiTask()).as("「%s」", query).isTrue();
        }

        @Test
        @DisplayName("召回到两个能力但没有任何连接信号，不判多任务")
        void ambiguityAloneIsNotMultiTask() {
            // 「换卡」同时召回换卡与卡片挂失，属于候选冲突（LOW_MARGIN），不是多任务。
            // 两者的出口都是慢路径，但原因码不同，混淆会让看板上的多意图占比虚高
            MultiTaskDetector.Signal signal = DETECTOR.detect("换卡", TWO_CAPABILITIES, true);

            assertThat(signal.multiTask()).isFalse();
        }
    }

    @Nested
    @DisplayName("召回不可信时退回纯词表，宁可错杀")
    class RecallDegraded {

        @Test
        @DisplayName("语义通道降级时，只凭条件词也判多任务")
        void lexicalSignalAloneSufficesWhenRecallDegraded() {
            MultiTaskDetector.Signal signal =
                    DETECTOR.detect("查余额，再给老徐转 1000；不足就别转", ONE_CAPABILITY, false);

            assertThat(signal.multiTask()).isTrue();
            assertThat(signal.evidence()).isEqualTo("multitask:conditional");
        }

        @Test
        @DisplayName("没有词表信号时，召回不可信也不会凭空判多任务")
        void noLexicalSignalStaysSingleTask() {
            MultiTaskDetector.Signal signal = DETECTOR.detect("查一下余额", ONE_CAPABILITY, false);

            assertThat(signal.multiTask()).isFalse();
            assertThat(signal.evidence()).isEqualTo("multitask:none");
        }
    }

    @Test
    @DisplayName("条件依赖与并列同时出现时，证据取条件依赖")
    void conditionalTakesPrecedenceInEvidence() {
        MultiTaskDetector.Signal signal =
                DETECTOR.detect("查余额，再给老徐转 1000；不足就别转", TWO_CAPABILITIES, true);

        assertThat(signal.hasConjunction()).isTrue();
        assertThat(signal.hasConditional()).isTrue();
        assertThat(signal.evidence()).isEqualTo("multitask:conditional");
    }
}
