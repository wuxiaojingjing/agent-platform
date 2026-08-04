package com.huawei.finance.a2a.node;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 归属判定的真值是 {@code supports}，不是能力 ID 的形状。
 *
 * <p>这两条用例直接取自资产里的反例。第一版按 {@code cap.<域码>.} 前缀判归属，
 * 这两条能力会被所有域判成「不是我的」——一次完全正确的派单被回成 NOT_MINE，
 * 入口改投一次之后仍然无人接，用户看到的是「暂不支持转账」。
 */
class CapabilityOwnershipTest {

    @Test
    @DisplayName("cap.transfer 没有域名段，转账域照样认领")
    void capabilityWithoutDomainSegmentIsStillOwned() {
        var node = node("agent.transfer", "transfer", Set.of("cap.transfer"));

        var receipt = node.handle(task("agent.transfer", "cap.transfer"));

        assertThat(receipt.outcome())
                .as("按前缀判会是 NOT_MINE：cap.transfer 里没有 transfer 段之后的域名结构")
                .isEqualTo(DelegationOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("cap.card.replace 被账户域与信用卡域同时承接，两边都认领")
    void sharedCapabilityIsOwnedByBothDomains() {
        // 换卡这件事两边都办。按前缀判，它既不属于 account 也不属于 creditcard_service
        var account = node("agent.account", "account", Set.of("cap.card.replace"));
        var creditcard = node("agent.creditcard", "creditcard_service",
                Set.of("cap.card.replace"));

        assertThat(account.handle(task("agent.account", "cap.card.replace")).outcome())
                .isEqualTo(DelegationOutcome.SUCCEEDED);
        assertThat(creditcard.handle(task("agent.creditcard", "cap.card.replace")).outcome())
                .isEqualTo(DelegationOutcome.SUCCEEDED);
    }

    @Test
    @DisplayName("真不属于本域的能力：仍然回 NOT_MINE，没有被放宽")
    void genuinelyForeignCapabilityIsStillNotMine() {
        // 上面两条放宽了归属，这条证明没有放宽成「什么都认」
        var node = node("agent.transfer", "transfer", Set.of("cap.transfer"));

        var receipt = node.handle(task("agent.transfer", "cap.insurance.product.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.NOT_MINE);
    }

    @Test
    @DisplayName("本域的能力但未开放：DOMAIN_NOT_OPEN，与 NOT_MINE 分得开")
    void ownDomainButUnopenedIsNotOpen() {
        // 归属放宽之后这条尤其要守：若归属这步就否掉，
        // 「交付进度」和「域路由判错」会被压成同一个结局
        var node = node("agent.account", "account", Set.of("cap.account.balance.query"));

        var receipt = node.handle(task("agent.account", "cap.account.transaction.query"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.DOMAIN_NOT_OPEN);
    }

    private static DomainAgentNode node(String agentId, String domain, Set<String> supported) {
        TechDomainAgent agent = new TechDomainAgent() {
            @Override
            public String agentId() {
                return agentId;
            }

            @Override
            public String techDomainCode() {
                return domain;
            }

            @Override
            public boolean supports(String capabilityId) {
                return supported.contains(capabilityId);
            }

            @Override
            public TaskResult execute(UnifiedTask task) {
                return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS,
                        Enums.FailureClass.NONE, Map.of("done", "true"),
                        task.idempotencyKey(), GuardrailCheck.passed());
            }
        };
        return new DomainAgentNode(agentId, List.of(domain),
                new DomainAgentExecutor(agent, new KeywordGoalResolver(Map.of())), true);
    }

    private static DelegationEnvelope task(String target, String capabilityId) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "t", "mobile-banking-assistant",
                target, "root", "parent", "src", "d-" + target + capabilityId, "trace",
                DelegationMode.TASK, null, capabilityId, Map.of(), List.of(),
                Instant.parse("2025-07-28T10:00:30Z"), List.of("mobile-banking-assistant"));
    }
}
