package com.huawei.finance.slowpath;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RiskLevel;
import com.openjiuwen.core.foundation.tool.ToolCard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 能力卡 → OJ 工具卡投影。
 *
 * <p>可执行版 CapabilityTool 已删除（慢路径只规划不执行，ADR-004）。此处只守投影口径。
 */
class CapabilityToolCardsTest {

    private static CapabilityCard card(RiskLevel risk) {
        return new CapabilityCard("cap.transfer", "转账", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.transfer", List.of("transfer"), "给指定收款人转账",
                List.of("转账", "汇款"),
                Map.of("type", "object", "properties", Map.of("payee", Map.of("type", "string"))),
                Map.of(), List.of(), List.of("资金变动"), risk, 3000,
                Enums.Idempotency.REQUIRED, "payment-team", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of("给老徐转 1000"), List.of("转账"),
                List.of("payee", "amount"), null);
    }

    @Test
    @DisplayName("工具卡描述里不带风险等级：那是中控的执法依据，不是给模型的推理材料")
    void riskLevelIsNotExposedToTheModel() {
        ToolCard toolCard = CapabilityToolCards.toToolCard(card(RiskLevel.R2));

        assertThat(toolCard.getDescription()).doesNotContain("R2");
        assertThat(toolCard.getDescription()).contains("能力标识：cap.transfer");
        assertThat(toolCard.getId()).matches("^[a-zA-Z0-9_-]+$");
        assertThat(toolCard.getInputParams()).containsKey("properties");
    }

    @Test
    @DisplayName("适用意图写入描述，便于模型选工具")
    void supportedIntentsAppearInDescription() {
        ToolCard toolCard = CapabilityToolCards.toToolCard(card(RiskLevel.R0));
        assertThat(toolCard.getDescription()).contains("转账").contains("汇款");
    }

    @Test
    @DisplayName("空参数能力仍投影为模型协议要求的 object JSON Schema")
    void emptyInputSchemaBecomesObjectSchema() {
        CapabilityCard source = card(RiskLevel.R0);
        CapabilityCard noParameters = new CapabilityCard(source.capabilityId(), source.name(),
                source.type(), source.granularity(), source.parentCapabilityId(), source.domains(),
                source.description(), source.supportedIntents(), Map.of(), source.outputSchema(),
                source.preconditions(), source.sideEffects(), source.riskLevel(), source.timeoutMs(),
                source.idempotency(), source.owner(), source.version(), source.status(),
                source.utterances(), source.keywords(), List.of(), source.guardrailOwner(),
                source.principalRequired());

        assertThat(CapabilityToolCards.toToolCard(noParameters).getInputParams())
                .containsEntry("type", "object")
                .containsEntry("properties", Map.of());
    }
}
