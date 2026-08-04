package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.fastpath.rewrite.ChineseAnalyzer;
import com.huawei.finance.fastpath.rewrite.QueryRewriter;
import com.huawei.finance.fastpath.rewrite.RewriteResult;
import com.huawei.finance.registry.asset.AssetBundle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-11：改写归一。
 *
 * <p>此前这一层只被端到端间接覆盖，没有独立用例——于是「哪个字段该给谁」这条唯一重要的
 * 规矩，代码里全靠注释维持。改写本身很简单，容易出错的是**分发**：把改写后的句子拿去渲染，
 * 用户会看到自己没说过的话；把它拿去做向量召回，会抹掉与文档逐字相同的那次匹配。
 *
 * <p>用真实资产而非测试词表：要验的正是线上那份 {@code synonyms.yaml} 的效果。
 */
class QueryRewriterTest {

    private static QueryRewriter rewriter;

    @BeforeAll
    static void setUp() {
        AssetBundle bundle = FastPathFixture.assets();
        rewriter = new QueryRewriter(bundle.synonyms(), new ChineseAnalyzer());
    }

    @Nested
    @DisplayName("改写本身")
    class Rewriting {

        @Test
        @DisplayName("同义表命中后 normalized 变更")
        void synonymsApply() {
            RewriteResult r = rewriter.rewrite("卡里还有多少钱");

            assertThat(r.normalized()).isEqualTo("余额");
        }

        @Test
        @DisplayName("纠错在同义替换之前，否则以纠正后写法为键的条目一条也命中不了")
        void correctionsRunBeforeReplacements() {
            // 「帐单」先纠成「账单」，规则与 BM25 侧才认得出
            assertThat(rewriter.rewrite("帮我查下帐单").normalized()).contains("账单");
            assertThat(rewriter.rewrite("信用咔的帐户").normalized())
                    .contains("信用卡").contains("账户");
        }

        @Test
        @DisplayName("长表述优先命中，不被短词抢先替换")
        void longestMatchWins() {
            // 「卡里还有多少钱」若被「还有多少钱」抢先替换，会剩下「卡里余额」
            assertThat(rewriter.rewrite("卡里还有多少钱").normalized()).isEqualTo("余额");
        }

        @Test
        @DisplayName("停用词只在 searchText 里剔除，normalized 保留")
        void stopwordsOnlyAffectSearchText() {
            RewriteResult r = rewriter.rewrite("帮我查一下账单");

            assertThat(r.searchText()).doesNotContain("帮我").doesNotContain("一下");
            assertThat(r.normalized()).contains("帮我");
        }

        @Test
        @DisplayName("空输入不炸，各字段为空串")
        void blankInputIsSafe() {
            RewriteResult r = rewriter.rewrite("   ");

            assertThat(r.original()).isEmpty();
            assertThat(r.normalized()).isEmpty();
            assertThat(r.searchText()).isEmpty();
            assertThat(r.semanticText()).isEmpty();
        }
    }

    @Nested
    @DisplayName("字段各归各的去处")
    class FieldRouting {

        /** 面客渲染只能用 original，否则用户会看到自己没说过的话（v0.7 §2.5.4）。 */
        @Test
        @DisplayName("original 保留面客原文，一个字不改")
        void originalIsUntouched() {
            String raw = "帮我查一下帐单，卡里还有多少钱";

            assertThat(rewriter.rewrite(raw).original()).isEqualTo(raw);
        }

        /**
         * FP-11 的核心判定：**纠错与同义替换不得污染进入语义通道的文本**。
         *
         * <p>不是洁癖。{@code cap.account.balance.query} 的 utterances 里逐字写着
         * 「卡里还有多少钱」；用户恰好说出这一句时，改写会把它变成「余额」，
         * 于是我们拿一个改写结果去和原句做向量匹配——把能拿到的最高分亲手抹掉了。
         */
        @Test
        @DisplayName("semanticText 不经同义替换，与用户原话一致")
        void semanticTextEscapesReplacements() {
            RewriteResult r = rewriter.rewrite("卡里还有多少钱");

            assertThat(r.normalized()).isEqualTo("余额");
            assertThat(r.semanticText()).isEqualTo("卡里还有多少钱");
        }

        @Test
        @DisplayName("semanticText 也不经纠错替换")
        void semanticTextEscapesCorrections() {
            RewriteResult r = rewriter.rewrite("查下帐单");

            assertThat(r.normalized()).contains("账单");
            assertThat(r.semanticText()).isEqualTo("查下帐单");
        }

        @Test
        @DisplayName("terms 是分词结果而非整句，否则 keywords 的 terms 查询永远不命中")
        void termsAreSegmented() {
            RewriteResult r = rewriter.rewrite("查一下账户余额");

            assertThat(r.terms()).isNotEmpty();
            assertThat(r.terms()).doesNotContain("查一下账户余额");
            assertThat(r.terms()).contains("余额");
        }
    }
}
