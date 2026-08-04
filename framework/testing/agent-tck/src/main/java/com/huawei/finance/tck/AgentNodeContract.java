package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentNode} 的契约测试包（架构草案 v0.3 阶段 3a 门禁）。
 *
 * <p>行内交付一个科技域节点之后继承本类,跑绿即算合规。本包守的是**跨 A2A 的可信性**:
 * 网关那侧已经有强制信封兜底，但那是最后一道——一个节点若在这里就守不住，
 * 它在生产上的表现是「委托总被网关拒掉」，而不是「办不成」，排障方向完全不同。
 *
 * <p>子类只需实现 {@link #node()} 与 {@link #ownedCapabilityId()}。
 */
public abstract class AgentNodeContract {

    /** 被测节点。 */
    protected abstract AgentNode node();

    /** 本域确实承接的一个能力 ID，用于构造属于本域的 TASK。 */
    protected abstract String ownedCapabilityId();

    /** 明确不属于本域的能力 ID。默认给一个显然的外域值，域侧可覆写。 */
    protected String foreignCapabilityId() {
        return "cap.definitely_not_this_domain.nothing.query";
    }

    @Test
    @DisplayName("契约：agentId 稳定且非空")
    void agentIdIsStable() {
        AgentNode node = node();

        assertThat(node.agentId()).isNotBlank();
        assertThat(node.agentId())
                .as("agentId 是 A2A 路由键，两次调用必须一致")
                .isEqualTo(node.agentId());
    }

    @Test
    @DisplayName("契约：本域的 TASK 必须回一份合规信封")
    void ownedTaskReturnsValidEnvelope() {
        DelegationEnvelope envelope = task(ownedCapabilityId());

        DelegationReceipt receipt = node().handle(envelope);

        assertThat(receipt).as("不得返回 null——网关会把它判成 FATAL").isNotNull();
        assertThat(receipt.version()).isEqualTo(DelegationEnvelope.CURRENT_VERSION);
        assertThat(receipt.delegationId())
                .as("回执必须带回本次 delegationId，不能是别的委托的")
                .isEqualTo(envelope.delegationId());
        assertThat(receipt.outcome()).isNotNull();
    }

    @Test
    @DisplayName("契约：不属于本域的 TASK 回 NOT_MINE，不回笼统失败")
    void foreignTaskIsNotMine() {
        // 归因:回笼统失败之后，入口判错会被永远计成「域没做完」，
        // 而两者的 Owner 和修法完全不同（§7.1）
        DelegationReceipt receipt = node().handle(task(foreignCapabilityId()));

        assertThat(receipt.outcome())
                .as("外域能力应回 NOT_MINE 让入口改投，而不是 FATAL / DOMAIN_NOT_OPEN")
                .isEqualTo(DelegationOutcome.NOT_MINE);
    }

    @Test
    @DisplayName("契约：声称 SUCCEEDED 就必须给结构化事实")
    void successAlwaysCarriesStructuredFacts() {
        // 这条防的是那句「已为您转账 1000 元」:自由文本再像样也不是事实（§8.6）
        DelegationReceipt receipt = node().handle(task(ownedCapabilityId()));

        if (receipt.outcome() == DelegationOutcome.SUCCEEDED) {
            assertThat(receipt.facts())
                    .as("SUCCEEDED 而事实为空，网关会整单判 FATAL")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("契约：NEED_USER 必须给结构化缺槽，不回面客话术")
    void needUserCarriesStructuredSlots() {
        DelegationReceipt receipt = node().handle(task(ownedCapabilityId()));

        if (receipt.outcome() == DelegationOutcome.NEED_USER) {
            assertThat(receipt.missingSlots())
                    .as("只有入口与用户对话，域节点回槽位不回话术（§7 第 1、4 条）")
                    .isNotEmpty();
            assertThat(receipt.missingSlots()).allSatisfy(slot ->
                    assertThat(slot.slot()).isNotBlank());
        }
    }

    @Test
    @DisplayName("契约：同一 delegationId 二次到达不得产生第二笔副作用")
    void sameDelegationIdIsIdempotent() {
        // 下游本地幂等键只对它自己的子任务有效，不参与入站去重——
        // 入站去重的唯一依据是 delegationId（§6.2 第 1 条）
        DelegationEnvelope envelope = task(ownedCapabilityId());

        DelegationReceipt first = node().handle(envelope);
        DelegationReceipt second = node().handle(envelope);

        assertThat(second.outcome())
                .as("二次到达应返回首次结局，包括首次是 PARTIAL 的情况")
                .isEqualTo(first.outcome());
        assertThat(second.facts()).isEqualTo(first.facts());
    }

    @Test
    @DisplayName("契约：非自治节点收到 GOAL 必须拒绝，不猜一个能力去执行")
    void nonAutonomousRejectsGoal() {
        AgentNode node = node();
        if (node.autonomous()) {
            return;
        }

        DelegationReceipt receipt = node.handle(goal("随便办点什么"));

        assertThat(receipt.outcome()).isEqualTo(DelegationOutcome.FATAL);
    }

    @Test
    @DisplayName("契约：handle 不抛异常——异常穿透会让委托既没回执也没终态")
    void handleNeverThrows() {
        assertThatCode(() -> node().handle(task(foreignCapabilityId())))
                .doesNotThrowAnyException();
        assertThatCode(() -> node().handle(goal("一句本域大概办不了的话")))
                .doesNotThrowAnyException();
    }

    private DelegationEnvelope task(String capabilityId) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "tck-tenant",
                "mobile-banking-assistant", node().agentId(), "root-tck", "parent-tck", "src-tck",
                "tck-" + UUID.nameUUIDFromBytes(capabilityId.getBytes()), "trace-tck",
                DelegationMode.TASK, null, capabilityId, Map.of(), List.of(),
                Instant.now().plusSeconds(30), List.of("mobile-banking-assistant"));
    }

    private DelegationEnvelope goal(String goal) {
        return new DelegationEnvelope(DelegationEnvelope.CURRENT_VERSION, "tck-tenant",
                "mobile-banking-assistant", node().agentId(), "root-tck", "parent-tck", "src-tck",
                "tck-goal-" + UUID.nameUUIDFromBytes(goal.getBytes()), "trace-tck",
                DelegationMode.GOAL, goal, null, Map.of(), List.of(),
                Instant.now().plusSeconds(30), List.of("mobile-banking-assistant"));
    }
}
