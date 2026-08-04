package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.fastpath.arbitration.DateTable;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * FP-12 的另一半：仲裁 prompt 的日期基准（v0.7 §2.7.6）。
 *
 * <p>「上个月的账单」这类说法在模型权重里没有答案——它不知道今天几号。不给基准，
 * 它要么留空（多问一句本可以不问的话），要么按训练语料推一个看着合理的错日期。
 *
 * <p>所有用例都走固定时钟。日期是最容易写出「只在某几天绿」的用例的地方，
 * 而那种红会被当成抖动而不是缺陷。
 */
class DateTableTest {

    /** 2026-03-15（东八区）。上月落在 2 月，月末是 28 还是 29 得真算。 */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-03-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Nested
    @DisplayName("表本身")
    class Rendering {

        @Test
        @DisplayName("今天昨天前天按固定时钟算")
        void relativeDays() {
            String table = DateTable.render(FIXED);

            assertThat(table).contains("今天=2026-03-15");
            assertThat(table).contains("昨天=2026-03-14");
            assertThat(table).contains("前天=2026-03-13");
        }

        @Test
        @DisplayName("本月区间从 1 号到今天，不是到月末——账单还没出完")
        void currentMonthEndsToday() {
            assertThat(DateTable.render(FIXED)).contains("本月=2026-03-01~2026-03-15");
        }

        /** 跨月边界是这张表存在的主要理由：它交给模型算就是让它猜 2 月有几天。 */
        @Test
        @DisplayName("上月区间落在 2 月且月末算准（2026 非闰年，28 号）")
        void previousMonthHandlesShortMonth() {
            assertThat(DateTable.render(FIXED)).contains("上月=2026-02-01~2026-02-28");
        }

        @Test
        @DisplayName("闰年 2 月末是 29 号")
        void leapYear() {
            Clock leap = Clock.fixed(Instant.parse("2028-03-10T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

            assertThat(DateTable.render(leap)).contains("上月=2028-02-01~2028-02-29");
        }

        @Test
        @DisplayName("跨年时上月是去年 12 月")
        void januaryLooksBackToLastYear() {
            Clock january = Clock.fixed(Instant.parse("2026-01-08T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

            assertThat(DateTable.render(january)).contains("上月=2025-12-01~2025-12-31");
        }

        @Test
        @DisplayName("最近 7 天含今天共 7 天，最近 30 天同理")
        void rollingWindowsAreInclusive() {
            String table = DateTable.render(FIXED);

            assertThat(table).contains("最近7天=2026-03-09~2026-03-15");
            assertThat(table).contains("最近30天=2026-02-14~2026-03-15");
        }

        /** 它每次请求都要占字符预算，涨起来是要花钱的。 */
        @Test
        @DisplayName("表很小，不该侵占 prompt 预算")
        void staysTiny() {
            assertThat(DateTable.render(FIXED).length()).isLessThan(200);
        }
    }

    @Nested
    @DisplayName("注入 prompt")
    class Injection {

        @Test
        @DisplayName("日期基准出现在实际发给模型的 prompt 里")
        void promptCarriesTheTable() {
            CapturingGateway gateway = new CapturingGateway();
            FastPathFixture.build(gateway).engine().decide(new FastPathRequest(
                    new RequestContext("trace-date", "s-date", "u-1", "MOBILE_BANK", "home", "", false),
                    "查一下上个月的交易明细", null, Map.of()));

            assertThat(gateway.lastUserPrompt).isNotNull();
            // FastPathFixture 用的是同一个固定时钟
            assertThat(gateway.lastUserPrompt).contains("今天=2026-03-15");
            assertThat(gateway.lastUserPrompt).contains("上月=2026-02-01~2026-02-28");
        }

        /** 占位符没被替换会以字面量形式留在 prompt 里，模型读到的就是一行乱码。 */
        @Test
        @DisplayName("模板占位符全部被替换，没有残留")
        void noUnreplacedPlaceholders() {
            CapturingGateway gateway = new CapturingGateway();
            FastPathFixture.build(gateway).engine().decide(new FastPathRequest(
                    new RequestContext("trace-date2", "s-date2", "u-1", "MOBILE_BANK", "home", "", false),
                    "查一下上个月的交易明细", null, Map.of()));

            assertThat(gateway.lastUserPrompt).doesNotContain("{{").doesNotContain("}}");
        }
    }

    private static final class CapturingGateway implements ModelGatewayClient {

        private static final String RESPONSE = """
                {"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION","candidateIds":["cap.account.transaction.query"],
                 "confidence":0.9,"reasonCode":"HIGH_CONFIDENCE","extractedSlots":{}}
                """;

        private String lastUserPrompt;

        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            List<float[]> vectors = new ArrayList<>(inputs.size());
            inputs.forEach(i -> vectors.add(new float[1024]));
            return GatewayResult.ok(vectors, 1);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            lastUserPrompt = request.userPrompt();
            return GatewayResult.ok(RESPONSE, 1);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("stub", 0);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
