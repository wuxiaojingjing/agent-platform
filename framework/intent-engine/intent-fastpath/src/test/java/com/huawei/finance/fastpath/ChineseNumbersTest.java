package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.fastpath.rewrite.ChineseNumbers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 中文金额读法。
 *
 * <p>这一组用例的分量比条数看起来重：金额读错在转账场景里不是精度问题。
 * 因此除了「读得对」，同等篇幅在验「读不准时必须返回空」。
 */
class ChineseNumbersTest {

    @Nested
    @DisplayName("口语金额")
    class Colloquial {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "两千, 2000",
                "俩千, 2000",
                "一千, 1000",
                "五百, 500",
                "三万, 30000",
                "十万, 100000",
                "一百万, 1000000",
                "五十, 50",
                "十五, 15",
                "十, 10",
                "八百五十, 850",
                "一千二百三十四, 1234",
                "两万三千, 23000",
                "一亿, 100000000",
        })
        void reads(String text, String expected) {
            assertThat(ChineseNumbers.parse(text)).hasToString(expected);
        }

        /**
         * 「一万五」的尾数省略了单位。
         *
         * <p>口语里它是 15000 而不是 10005。这条单列是因为按字面逐位累加的实现会读成后者，
         * 而 15000 与 10005 差着一个数量级。
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "一万五, 15000",
                "两万三, 23000",
                "一万五千, 15000",
                "三千五, 3500",
                "八百五, 850",
        })
        void omittedTrailingUnit(String text, String expected) {
            assertThat(ChineseNumbers.parse(text)).hasToString(expected);
        }

        /** 「零」的作用正是宣告后面那位落在个位：三千五是 3500，三千零五是 3005。 */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "三千零五, 3005",
                "一百零五, 105",
                "一千零五十, 1050",
        })
        void zeroPinsTheTrailingDigitToTheOnesPlace(String text, String expected) {
            assertThat(ChineseNumbers.parse(text)).hasToString(expected);
        }
    }

    @Nested
    @DisplayName("读不准就返回空，交给澄清")
    class RefusesToGuess {

        @ParameterizedTest
        @ValueSource(strings = {"", "余额", "转账", "千", "万", "百万"})
        void returnsNull(String text) {
            assertThat(ChineseNumbers.parse(text)).isNull();
        }

        /** 「一一」在金额里没有确定读法，宁可拒绝也不能猜成 11 或 1。 */
        @Test
        void ambiguousDigitRunIsRejected() {
            assertThat(ChineseNumbers.parse("一一")).isNull();
        }
    }

    @Nested
    @DisplayName("从整句里取数")
    class FromSentence {

        @Test
        void findsAmountInUtterance() {
            assertThat(ChineseNumbers.findFirst("给我老板转两千")).isEqualTo("2000");
            assertThat(ChineseNumbers.findFirst("帮我转一万五给张伟")).isEqualTo("15000");
        }

        /** 纯阿拉伯数字不归这里管，避免与正则抽取互相打架抽出两个不同的值。 */
        @Test
        void skipsArabicDigits() {
            assertThat(ChineseNumbers.findFirst("转 1000 元")).isNull();
        }

        /**
         * 「一」在汉语里多数时候不是数目。
         *
         * <p>不加限制的话「查一下余额」会抽出金额 1，而这句话跟钱毫无关系。
         * 所以只认带量级词或紧跟货币量词的数字串。
         */
        @ParameterizedTest
        @ValueSource(strings = {"查一下余额", "看一眼账单", "帮我查查", "一起看看"})
        void returnsNullWhenNoNumber(String text) {
            assertThat(ChineseNumbers.findFirst(text)).isNull();
        }

        /** 不带量级词但紧跟货币量词，仍然是金额。 */
        @Test
        void acceptsBareDigitWithCurrencyUnit() {
            assertThat(ChineseNumbers.findFirst("给他转五块")).isEqualTo("5");
            assertThat(ChineseNumbers.findFirst("转八元")).isEqualTo("8");
        }
    }
}
