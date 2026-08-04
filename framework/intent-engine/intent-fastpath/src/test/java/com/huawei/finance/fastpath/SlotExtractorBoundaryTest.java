package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.fastpath.rewrite.ChineseAnalyzer;
import com.huawei.finance.fastpath.rewrite.SlotExtractor;
import com.huawei.finance.registry.asset.ClarifyConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-12 边界用例：大写金额、带分隔符与跨行长度的卡号。
 *
 * <p>常规写法（「给张三转 1000」）早已被端到端覆盖。这里补的是**用户真实会打出来、
 * 而正则容易读错**的那些形态。金额与卡号是全系统里唯二「读错就直接造成资金后果」的槽位，
 * 所以判定分两类写：读得对，以及**读不准时必须留空**。留空会多问一句，读错会转错钱。
 */
class SlotExtractorBoundaryTest {

    private static SlotExtractor extractor;
    private static ChineseAnalyzer analyzer;

    @BeforeAll
    static void setUp() {
        extractor = new SlotExtractor();
        analyzer = new ChineseAnalyzer();
    }

    private static Map<String, Object> extract(String text) {
        return extractor.extract(text, analyzer.analyze(text));
    }

    @Nested
    @DisplayName("大写与口语金额")
    class Amounts {

        @Test
        @DisplayName("大写金额「壹仟元」读成 1000")
        void capitalizedAmount() {
            assertThat(extract("给张三转壹仟元")).containsEntry("amount", "1000");
        }

        @Test
        @DisplayName("大写「贰佰伍拾」读成 250")
        void capitalizedWithTens() {
            assertThat(extract("给张三转贰佰伍拾元")).containsEntry("amount", "250");
        }

        /**
         * 千分位是网银里最常见的粘贴形态。正则 {@code \d+} 遇到逗号就停，
         * 「1,000」会被读成 1——方向上是少转，但仍然是读错了金额。
         */
        @Test
        @DisplayName("带千分位的「1,000」读成 1000，不能截成 1")
        void thousandsSeparator() {
            assertThat(extract("给张三转 1,000 元")).containsEntry("amount", "1000");
        }

        @Test
        @DisplayName("带千分位与小数的「12,345.67」完整读出")
        void thousandsSeparatorWithDecimals() {
            assertThat(extract("给张三转 12,345.67 元")).containsEntry("amount", "12345.67");
        }

        @Test
        @DisplayName("小数照常")
        void decimals() {
            assertThat(extract("给张三转 99.50 元")).containsEntry("amount", "99.50");
        }

        @Test
        @DisplayName("产品编号不得被当成金额")
        void productCodeIsNotAmount() {
            assertThat(extract("对比产品B和产品B2")).doesNotContainKey("amount");
        }

        @Test
        @DisplayName("汇款错误码不得被当成金额")
        void remittanceErrorCodeIsNotAmount() {
            assertThat(extract("汇款报错26023")).doesNotContainKey("amount");
        }

        @Test
        @DisplayName("资金动作后的无单位数字仍是金额")
        void unitlessAmountAfterMoneyOperation() {
            assertThat(extract("给张三转1000")).containsEntry("amount", "1000");
        }

        @Test
        @DisplayName("带单位的还款金额正常识别")
        void repaymentAmountWithUnit() {
            assertThat(extract("还款1000元")).containsEntry("amount", "1000");
        }

        @Test
        @DisplayName("上下文指代交给模型；确定性抽槽器仅保留收款人")
        void contextualSemanticsAreNotHardCodedAsMarkers() {
            assertThat(extract("用第二张卡转一半给张三"))
                    .containsEntry("payee", "张三")
                    .doesNotContainKeys("amount", "fromAccount", "accountOrdinal", "amountBasis");
        }
    }

    @Nested
    @DisplayName("卡号")
    class CardNumbers {

        @Test
        @DisplayName("连写 16 位卡号")
        void plainSixteen() {
            assertThat(extract("用 6222021234567890 这张卡"))
                    .containsEntry("cardNumber", "6222021234567890");
        }

        @Test
        @DisplayName("19 位卡号（部分行的卡长这样）")
        void nineteenDigits() {
            assertThat(extract("用 6222021234567890123 这张卡"))
                    .containsEntry("cardNumber", "6222021234567890123");
        }

        /**
         * 从网银或短信里复制出来的卡号常带空格。此前这种写法不被卡号正则识别，
         * 而它落到金额正则手里会被读成「6222」——一个带分隔符的卡号变成了一笔转账金额。
         */
        @Test
        @DisplayName("空格分隔的卡号被识别，且不得被当成金额")
        void spaceSeparated() {
            Map<String, Object> slots = extract("用 6222 0212 3456 7890 这张卡");

            assertThat(slots).containsEntry("cardNumber", "6222021234567890");
            assertThat(slots).doesNotContainKey("amount");
        }

        @Test
        @DisplayName("短横线分隔的卡号同样识别")
        void hyphenSeparated() {
            assertThat(extract("卡号 6222-0212-3456-7890"))
                    .containsEntry("cardNumber", "6222021234567890");
        }

        /**
         * 位数不够的数字串不是卡号。这条要守住方向：宁可不认，也不能把一串
         * 恰好 12 位的订单号认成卡号下发出去。
         */
        @Test
        @DisplayName("位数不足的数字串不认作卡号")
        void tooShortIsNotACard() {
            assertThat(extract("订单号 123456789012")).doesNotContainKey("cardNumber");
        }
    }

    @Nested
    @DisplayName("金额与卡号同时出现时不得互相污染")
    class Interference {

        @Test
        @DisplayName("卡号先抽走，金额不会读到卡号的片段")
        void cardIsStrippedBeforeAmount() {
            Map<String, Object> slots = extract("用 6222021234567890 给张三转 500 元");

            assertThat(slots).containsEntry("cardNumber", "6222021234567890");
            assertThat(slots).containsEntry("amount", "500");
        }

        @Test
        @DisplayName("带空格卡号与金额并存时各归各位")
        void spacedCardAndAmount() {
            Map<String, Object> slots = extract("用 6222 0212 3456 7890 给张三转 500 元");

            assertThat(slots).containsEntry("cardNumber", "6222021234567890");
            assertThat(slots).containsEntry("amount", "500");
        }
    }

    @Nested
    @DisplayName("配置化集合词")
    class CollectionWords {

        @Test
        @DisplayName("成员在首轮直接填入 Runtime 规范值")
        void configuredMemberFillsCanonicalValue() {
            SlotExtractor configured = new SlotExtractor(collectionConfig());

            assertThat(configured.extract("我要换贷记卡"))
                    .containsEntry("cardType", "CREDIT");
            assertThat(configured.extract("工资卡换卡"))
                    .containsEntry("cardType", "DEBIT");
            assertThat(configured.extract("短期理财怎么选"))
                    .containsEntry("termPreference", "SHORT");
        }

        @Test
        @DisplayName("多个不同成员和未知成员都不猜值")
        void ambiguousAndUnknownMembersStayUnfilled() {
            SlotExtractor configured = new SlotExtractor(collectionConfig());

            assertThat(configured.extract("信用卡还是借记卡都可以"))
                    .doesNotContainKey("cardType");
            assertThat(configured.extract("公交卡换卡"))
                    .doesNotContainKey("cardType");
        }

        private ClarifyConfig collectionConfig() {
            ClarifyConfig.SlotClarify cardType = new ClarifyConfig.SlotClarify();
            cardType.setOptions(List.of("信用卡", "借记卡"));
            cardType.setValueMapping(Map.of(
                    "信用卡", "CREDIT", "贷记卡", "CREDIT",
                    "借记卡", "DEBIT", "储蓄卡", "DEBIT", "工资卡", "DEBIT"));
            ClarifyConfig.SlotClarify term = new ClarifyConfig.SlotClarify();
            term.setOptions(List.of("短期", "长期"));
            term.setValueMapping(Map.of("短期", "SHORT", "长期", "LONG"));
            ClarifyConfig config = new ClarifyConfig();
            config.setSlots(Map.of("cardType", cardType, "termPreference", term));
            return config;
        }
    }
}
