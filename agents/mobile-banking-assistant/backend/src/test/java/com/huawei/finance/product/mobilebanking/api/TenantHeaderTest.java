package com.huawei.finance.product.mobilebanking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.product.mobilebanking.api.TenantHeaders.Rejection;
import com.huawei.finance.product.mobilebanking.api.TenantHeaders.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** FP-65：渠道接入与租户头映射。 */
class TenantHeaderTest {

    private static Resolution resolve(String headerUser, String headerSpace, String headerChannel,
                                      String bodyUser, String bodyChannel) {
        return TenantHeaders.resolve(headerUser, headerSpace, headerChannel, bodyUser, bodyChannel);
    }

    @Nested
    @DisplayName("缺头即拒绝")
    class MissingHeaders {

        @Test
        void 缺用户头() {
            assertThat(resolve(null, "space-a", "APP", "u1", "APP").rejection())
                    .isEqualTo(Rejection.MISSING_USER_ID);
        }

        @Test
        void 缺租户头() {
            assertThat(resolve("u1", null, "APP", "u1", "APP").rejection())
                    .isEqualTo(Rejection.MISSING_SPACE_ID);
        }

        @Test
        @DisplayName("空白头等同缺失——空格不是一个租户")
        void 空白头等同缺失() {
            assertThat(resolve("u1", "   ", "APP", "u1", "APP").rejection())
                    .isEqualTo(Rejection.MISSING_SPACE_ID);
            assertThat(resolve("\t", "space-a", "APP", "u1", "APP").rejection())
                    .isEqualTo(Rejection.MISSING_USER_ID);
        }

        @Test
        @DisplayName("请求体带了 userId 也不能替代头：body 是客户端写的")
        void 请求体不能替代头() {
            assertThat(resolve(null, "space-a", "APP", "u1", "APP").rejected()).isTrue();
        }
    }

    @Nested
    @DisplayName("身份以头为准，冲突即拒绝")
    class Identity {

        @Test
        void 头与体一致时放行() {
            Resolution r = resolve("u1", "space-a", "APP", "u1", "APP");
            assertThat(r.rejected()).isFalse();
            assertThat(r.headers().userId()).isEqualTo("u1");
            assertThat(r.headers().spaceId()).isEqualTo("space-a");
        }

        @Test
        @DisplayName("不一致不是静默取头里那个，而是拒绝：上游有 bug 或有人在试，都该停住")
        void 不一致即拒绝() {
            assertThat(resolve("u1", "space-a", "APP", "u2", "APP").rejection())
                    .isEqualTo(Rejection.USER_ID_MISMATCH);
        }

        @Test
        @DisplayName("请求体不带 userId 时不算冲突——它本来就该由头提供")
        void 请求体缺省不算冲突() {
            Resolution r = resolve("u1", "space-a", "APP", null, "APP");
            assertThat(r.rejected()).isFalse();
            assertThat(r.headers().userId()).isEqualTo("u1");
        }
    }

    @Nested
    @DisplayName("渠道是唯一允许回落的一项")
    class Channel {

        @Test
        void 渠道头优先() {
            assertThat(resolve("u1", "space-a", "WECHAT", "u1", "APP").headers().channel())
                    .isEqualTo("WECHAT");
        }

        @Test
        @DisplayName("缺渠道头回落请求体：渠道只影响话术与缓存分片，不影响能看到什么数据")
        void 缺渠道头回落请求体() {
            assertThat(resolve("u1", "space-a", null, "u1", "APP").headers().channel())
                    .isEqualTo("APP");
        }

        @Test
        void 两处都缺时渠道为空() {
            assertThat(resolve("u1", "space-a", null, "u1", null).headers().channel()).isNull();
        }
    }
}
