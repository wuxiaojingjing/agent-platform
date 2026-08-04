package com.huawei.finance.fastpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.intent.IntentRequest;
import com.huawei.finance.intent.IntentResult;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentEngine 门面与直调 FastPathEngine 必须同结果。
 *
 * <p>门面是纯适配；一旦中间塞进「顺手改一下」，出口看板与中控会拿到两套口径。
 */
class IntentEngineFacadeTest {

    @Test
    @DisplayName("recognize 与 decide 在同一请求上逐字段一致")
    void recognizeMatchesDirectDecide() {
        // 两套独立装配：共用同一缓存会让第二次请求吃到 L1，假失败掩盖门面问题
        FastPathEngine engine = FastPathFixture.build().engine();
        IntentEngine facade = new FastPathIntentEngine(FastPathFixture.build().engine());

        RequestContext ctx = new RequestContext("trace-facade", "s-facade", "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", false);
        FastPathRequest directReq = new FastPathRequest(ctx, "查一下余额", null, Map.of());
        IntentRequest facadeReq = FastPathRequests.toIntentRequest(directReq);

        FastPathResult direct = engine.decide(directReq);
        IntentResult viaFacade = facade.recognize(facadeReq);

        assertThat(viaFacade.decision()).isEqualTo(direct.decision());
        assertThat(viaFacade.slots()).isEqualTo(direct.slots());
        assertThat(viaFacade.templateKey()).isEqualTo(direct.templateKey());
        // 门面只交出这两个字符串，不交出整个 RewriteResult——后者带着 HanLP 分词器的
        // 数据形状，放上门面等于连分词实现一起承诺。见 IntentResult#originalQuery。
        assertThat(viaFacade.originalQuery()).isEqualTo(direct.rewrite().original());
        assertThat(viaFacade.normalizedQuery()).isEqualTo(direct.rewrite().normalized());
        assertThat(viaFacade.event()).isEqualTo(direct.event());
        assertThat(viaFacade.intentPlan()).isEqualTo(direct.intentPlan());
        assertThat(viaFacade.recall() == null).isEqualTo(direct.recall() == null);
        if (direct.recall() != null) {
            assertThat(viaFacade.recall().candidates()).isEqualTo(direct.recall().candidates());
        }
    }

    @Test
    @DisplayName("静态条件计划经门面仍携带 intentPlan")
    void multiIntentPlanSurvivesFacade() {
        FastPathFixture.Built built = FastPathFixture.build();
        IntentEngine facade = new FastPathIntentEngine(built.engine());

        RequestContext ctx = new RequestContext("trace-facade-mi", "s-facade-mi", "u-1",
                "MOBILE_BANK", "home", "loginStatus=LOGGED_IN", false);
        IntentResult result = facade.recognize(new IntentRequest(
                ctx, "查余额，再给老徐转 1000；不足就别转", null, Map.of()));

        assertThat(result.decision().decision()).isEqualTo(com.huawei.finance.contracts.model.Decision.STATIC_PLAN);
        assertThat(result.decision().reasonCode()).isEqualTo(com.huawei.finance.contracts.model.ReasonCode.RESULT_RULE);
        assertThat(result.intentPlan())
                .as("门面不得丢掉已经完整解析的静态计划")
                .isNotNull();
        assertThat(result.intentPlan().items()).isNotEmpty();
        assertThat(result.intentPlan().fullyResolved()).isTrue();
    }
}
