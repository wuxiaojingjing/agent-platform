package com.huawei.finance.slowpath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 慢路径规划的边界。
 *
 * <p>不起真实 Agent 循环：那需要一个会按 function-calling 协议应答的模型，
 * 在单测里造出来的只能是一段写死的脚本，验的是脚本不是 Agent。这里验的是**边界**——
 * 规划工具到底会不会执行、提案里带没带执行许可。真实循环由联调覆盖。
 */
class SlowPathPlannerTest {

    private static CapabilityCard transfer() {
        return new CapabilityCard("cap.transfer", "转账", Enums.CapabilityType.TOOL,
                Enums.Granularity.TOOL, "agent.payment", List.of("payment"), "向指定收款人转账",
                List.of("转账"), Map.of("type", "object"), Map.of(), List.of(), List.of("资金划转"),
                RiskLevel.R2, 8000, Enums.Idempotency.REQUIRED, "支付领域", "1.0.0",
                Enums.CapabilityStatus.ACTIVE, List.of("给老徐转 1000"), List.of("转账"),
                List.of("payee", "amount"), null);
    }

    @Test
    @DisplayName("规划工具只记提案，一次都不执行")
    void planningToolNeverExecutes() {
        List<TaskProposal> sink = new ArrayList<>();
        ProposalTool tool = new ProposalTool(transfer(), sink);

        Object output = invoke(tool, Map.of("payee", "老徐", "amount", "1000"),
                Map.of("reason", "用户要求转账"));

        assertThat(sink).hasSize(1);
        assertThat(sink.get(0).capability().capabilityId()).isEqualTo("cap.transfer");
        assertThat(sink.get(0).slots()).containsEntry("payee", "老徐");
        assertThat(output).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .as("回执必须写明尚未执行，否则模型会据此推理成「钱已经转了」")
                .containsEntry("accepted", true);
        assertThat(String.valueOf(output)).contains("尚未执行");
    }

    @Test
    @DisplayName("提案里没有幂等键——那是中控在护栏放行之后才发的")
    void proposalsCarryNoExecutionPermit() {
        List<TaskProposal> sink = new ArrayList<>();
        invoke(new ProposalTool(transfer(), sink), Map.of(), Map.of());

        // TaskProposal 的字段只有能力、参数、理由。这条断言守的是它不要在某次「顺手补全」里
        // 长出 idempotencyKey 或 guardrail 字段：那会让 Agent 自带执行许可
        assertThat(TaskProposal.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("capability", "slots", "reason");
    }

    @Test
    @DisplayName("规划工具投影与 CapabilityToolCards 同源")
    void planningToolUsesSharedProjection() {
        CapabilityCard capability = transfer();
        ProposalTool planning = new ProposalTool(capability, new ArrayList<>());

        assertThat(planning.getCard().getId())
                .isEqualTo(CapabilityToolCards.toolId(capability.capabilityId()))
                .matches("^[a-zA-Z0-9_-]+$");
        assertThat(planning.getCard().getName()).isEqualTo(planning.getCard().getId());
        assertThat(planning.getCard().getDescription())
                .as("两份描述会漂移，漂移的表现是同一句话在快慢路径下选中了不同能力")
                .isEqualTo(CapabilityToolCards.toToolCard(capability).getDescription())
                .contains("能力名称：" + capability.name());
    }

    @Test
    @DisplayName("含中文的业务能力 ID 投影为唯一稳定的 ASCII 模型工具名")
    void nonAsciiCapabilityIdsBecomeSafeUniqueToolIds() {
        String first = CapabilityToolCards.toolId("cap.nav.fund_service_查询基金持仓");
        String second = CapabilityToolCards.toolId("cap.nav.fund_service_查询基金收益");

        assertThat(first).matches("^[a-zA-Z0-9_-]+$").hasSizeLessThanOrEqualTo(64);
        assertThat(second).matches("^[a-zA-Z0-9_-]+$").isNotEqualTo(first);
        assertThat(CapabilityToolCards.toolId("cap.nav.fund_service_查询基金持仓"))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("规划工具不提供流式")
    void planningToolHasNoStream() {
        assertThatThrownBy(() -> new ProposalTool(transfer(), new ArrayList<>()).stream(Map.of(), Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Object invoke(ProposalTool tool, Map<String, Object> inputs,
                                 Map<String, Object> kwargs) {
        try {
            return tool.invoke(inputs, kwargs);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
