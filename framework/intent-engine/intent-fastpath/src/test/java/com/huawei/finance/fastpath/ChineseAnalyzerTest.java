package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.fastpath.rewrite.ChineseAnalyzer;
import com.huawei.finance.fastpath.rewrite.SlotExtractor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * HanLP 在银行语料上的分词与实体识别。
 *
 * <p>这组用例同时是**能力边界记录**：HanLP portable 认得出什么、认不出什么，
 * 直接决定了收款人槽位有多少比例要走澄清。认不出来不是缺陷，
 * 缺陷是「认不出来却硬填一个」。
 */
class ChineseAnalyzerTest {

    private static ChineseAnalyzer analyzer;

    @BeforeAll
    static void loadDictionary() {
        analyzer = new ChineseAnalyzer();
    }

    @Nested
    @DisplayName("分词")
    class Segmentation {

        /**
         * 这条是接 HanLP 的直接动机。
         *
         * <p>`keywords` 是 keyword 类型字段，terms 查询要求整值相等。此前的实现按空白切分，
         * 而中文没有空白，切出来的仍是整句，与任何一个关键词都不相等——这条召回通道一直没命中过。
         */
        @Test
        void splitsChineseIntoRealWords() {
            List<String> words = analyzer.analyze("查一下信用卡账单").words();
            assertThat(words).contains("信用卡", "账单");
            assertThat(words).noneMatch(w -> w.length() > 4);
        }

        /** 单字词几乎不承载检索信号，却会让 terms 查询命中大量无关卡。 */
        @Test
        void dropsSingleCharactersAndParticles() {
            assertThat(analyzer.analyze("我要查一下我的余额").words())
                    .doesNotContain("我", "的", "要");
        }

        @Test
        void handlesEmptyInput() {
            assertThat(analyzer.analyze("").words()).isEmpty();
            assertThat(analyzer.analyze(null).entities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("实体识别（候选，不是真值）")
    class EntityRecognition {

        @Test
        void recognizesPersonName() {
            assertThat(analyzer.analyze("帮我转两千给张伟").entityWords()).contains("张伟");
        }

        /**
         * 「我老板」不是人名，HanLP 不该认出实体来。
         *
         * <p>这条是负例但很关键：如果这里冒出一个实体，收款人槽位就会被一个称谓填掉，
         * 而转账要的是具体的人。这种情况必须留空走澄清（或由仲裁模型按上下文补）。
         */
        @Test
        void doesNotInventEntityFromRoleWord() {
            assertThat(analyzer.analyze("给我老板转两千").entityWords()).doesNotContain("老板");
        }

        @Test
        void findsNoEntityInBalanceQuery() {
            assertThat(analyzer.analyze("查一下余额").entities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("实体补位收款人")
    class PayeeBackfill {

        private final SlotExtractor extractor = new SlotExtractor();

        private Map<String, Object> extract(String query) {
            return extractor.extract(query, analyzer.analyze(query));
        }

        /**
         * 正则抽不到的句式。
         *
         * <p>「转两千给张伟」曾是纯正则的盲区，后来为场景 5「转一半给张三」加的
         * {@code PAYEE_AFTER_AMOUNT}（动词与「给」之间容 0-12 字）把它一并覆盖了，
         * 于是这句不再是盲区。换一句仍在盲区里的：「张伟那边打两千过去」——
         * 收款人在动词之前且不带「给」，三个正则一个都不匹配。
         *
         * <p>刻意保留「纯正则抽不到」这半句断言。它是这条用例的**前提**：
         * 前提失效时用例必须失败，而不是悄悄退化成「分词和正则都能抽到」的重复验证。
         */
        @Test
        void fillsPayeeRegexCannotReach() {
            String query = "张伟那边打两千过去";
            assertThat(extractor.extract(query)).doesNotContainKey("payee");

            assertThat(extract(query))
                    .containsEntry("payee", "张伟")
                    .containsEntry("amount", "2000");
        }

        /**
         * 正则的 {2,4} 是贪婪的，会把金额一起吞进收款人。
         *
         * <p>这是接 HanLP 之前就存在的缺陷：「转给老徐一千」抽出的收款人是「老徐一千」。
         * 收窄成非贪婪又会把「王小明」截成「王小」，所以定位归正则、切边归分词。
         */
        @Test
        void trimsAmountSwallowedByGreedyRegex() {
            assertThat(extract("转给老徐一千"))
                    .containsEntry("payee", "老徐")
                    .containsEntry("amount", "1000");
        }

        @Test
        void keepsThreeCharacterName() {
            assertThat(extract("转给王小明五百")).containsEntry("payee", "王小明");
        }

        /** 「给我老板转两千」的正则捕获是「我老板」，前面那个「我」是代词不是姓。 */
        @Test
        void stripsLeadingPronoun() {
            assertThat(extract("给我老板转两千"))
                    .containsEntry("payee", "老板")
                    .containsEntry("amount", "2000");
        }

        /** 代词不是收款人。这同样是接 HanLP 之前就存在的缺陷。 */
        @Test
        void pronounIsNotAPayee() {
            assertThat(extract("给他们转钱")).doesNotContainKey("payee");
        }

        /**
         * 多个候选时一个都不填。
         *
         * <p>认出两个人名，挑哪一个都是猜。转账场景里抽错收款人的代价，远大于多问一句。
         */
        @Test
        void ambiguousEntitiesFillNothing() {
            ChineseAnalyzer.Analysis two = new ChineseAnalyzer.Analysis(List.of(),
                    List.of(new ChineseAnalyzer.Entity("张伟", "nr"),
                            new ChineseAnalyzer.Entity("李娜", "nr")),
                    List.of());
            assertThat(extractor.extract("这笔钱怎么分", two)).doesNotContainKey("payee");
        }

        @Test
        void noEntityFillsNothing() {
            assertThat(extract("查一下余额")).doesNotContainKey("payee");
        }

        @Test
        void nonPaymentEntityDoesNotBecomePayee() {
            assertThat(extract("了解换卡无忧")).doesNotContainKey("payee");
            assertThat(extract("了解张伟的基金产品")).doesNotContainKey("payee");
        }
    }
}
