package com.huawei.finance.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ResponseRealizerTest {

    private static AssetBundle assets;

    @BeforeAll
    static void loadAssets() {
        assets = new AssetLoader(new ContractValidator()).loadDefault();
    }

    @Test
    void templateModeNeverCallsModel() {
        int[] calls = {0};
        ResponseRealizer realizer = realizer(request -> { calls[0]++; return ResponseTextModel.Result.text("x", "m"); });
        RenderedResponse response = realizer.render(plan(Enums.RenderMode.TEMPLATE, List.of()));
        assertThat(response.text()).contains("100.00");
        assertThat(calls[0]).isZero();
    }

    @Test
    void modelSelectCanOnlyChooseApprovedTemplateForTheSamePhase() {
        ResponseRealizer realizer = realizer(request ->
                ResponseTextModel.Result.template("tpl.balance.result", "model-v1"));
        RenderedResponse response = realizer.render(plan(Enums.RenderMode.MODEL_SELECT,
                List.of("tpl.balance.result", "tpl.transfer.confirm")));
        assertThat(response.fellBack()).isFalse();
        assertThat(response.usedTemplateKey()).isEqualTo("tpl.balance.result");
    }

    @Test
    void polishReceivesVisibleHistoryAndPreservesCommittedNumbers() {
        ResponseRealizer realizer = realizer(request -> {
            assertThat(request.conversationHistory()).extracting(row -> row.get("role"))
                    .containsExactly("user", "assistant");
            return ResponseTextModel.Result.text("尾号3344借记卡当前可用余额为¥100.00。", "model-v1");
        });
        RenderedResponse response = realizer.render(plan(Enums.RenderMode.POLISH, List.of()),
                new ResponseModelContext(List.of(
                        Map.of("role", "user", "content", "查余额"),
                        Map.of("role", "assistant", "content", "正在查询")),
                        "查余额", Map.of("availableBalance", "100.00")));
        assertThat(response.fellBack()).isFalse();
        assertThat(response.text()).contains("100.00");
    }

    @Test
    void inventedNumberFallsBackToApprovedTemplate() {
        ResponseRealizer realizer = realizer(request ->
                ResponseTextModel.Result.text("您的余额为¥999.00。", "model-v1"));
        RenderedResponse response = realizer.render(plan(Enums.RenderMode.GENERATE, List.of()));
        assertThat(response.fellBack()).isTrue();
        assertThat(response.reason()).isEqualTo("model-number-invented");
        assertThat(response.text()).contains("100.00").doesNotContain("999.00");
    }

    private static ResponseRealizer realizer(ResponseTextModel model) {
        return new ResponseRealizer(assets, new TemplateVariableValidator(), new SimpleMeterRegistry(),
                AnswerAudit.passThrough(), model);
    }

    private static ResponsePlan plan(Enums.RenderMode mode, List<String> templates) {
        return ResponsePlan.builder()
                .traceId("trace-response").sceneCode("ACCOUNT_BALANCE")
                .responsePhase(Enums.ResponsePhase.FINAL)
                .templateKey("tpl.balance.result").templateVersion("1.0.0")
                .renderMode(mode).responseModel("model-v1")
                .approvedTemplateKeys(templates).responseTemperature(0.0).responseMaxTokens(128)
                .responsePolicyVersion("policy-v1").responsePromptVersion("prompt-v1")
                .slots(Map.of("accountAlias", "尾号3344借记卡", "availableBalance", "100.00", "currency", "¥"))
                .channel("MOBILE_BANK").fallbackTemplateKey("tpl.fallback.generic").build();
    }
}
