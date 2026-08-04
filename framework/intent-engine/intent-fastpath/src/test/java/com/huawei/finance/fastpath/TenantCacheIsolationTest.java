package com.huawei.finance.fastpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.fastpath.cache.DecisionCacheKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-65：租户必须参与一级出口缓存键。
 *
 * <p>这条如果破了，症状是跨租户拿到别人租户的出口，而单租户的测试环境永远复现不出来。
 */
class TenantCacheIsolationTest {

    private static RequestContext ctx(String spaceId) {
        return new RequestContext("t", "s", "u", spaceId, "APP", "home", "NORMAL", false);
    }

    private static String key(RequestContext ctx) {
        return DecisionCacheKey.of(ctx, "转账给张三", "asset-v1", "emb-v1", "instr-v1", "prompt-v1",
                List.of("userState"), Map.of("userState", "NORMAL"));
    }

    @Test
    @DisplayName("同一句话在不同租户下算出不同的键")
    void 不同租户不共键() {
        assertThat(key(ctx("space-a"))).isNotEqualTo(key(ctx("space-b")));
    }

    @Test
    @DisplayName("同租户同输入稳定命中，否则命中率归零")
    void 同租户同键() {
        assertThat(key(ctx("space-a"))).isEqualTo(key(ctx("space-a")));
    }

    @Test
    @DisplayName("未限定租户也是一个独立分片，不会与任何真实租户共键")
    void 未限定租户独立() {
        assertThat(key(ctx(RequestContext.SPACE_UNSCOPED))).isNotEqualTo(key(ctx("space-a")));
        assertThat(key(ctx(null))).isEqualTo(key(ctx(RequestContext.SPACE_UNSCOPED)));
    }
}
