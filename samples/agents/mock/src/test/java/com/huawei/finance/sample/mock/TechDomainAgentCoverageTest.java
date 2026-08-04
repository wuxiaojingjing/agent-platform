package com.huawei.finance.sample.mock;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一科技域一子 Agent：外部交付域 + 无 TOOL 脚手架覆盖附录 F 全量。
 *
 * <p>可执行叶子已迁至 {@code agents/*}；本模块仅保留 Scaffold 与夹具类。
 */
class TechDomainAgentCoverageTest {

    @Test
    @DisplayName("首批域已从 mock IMPLEMENTED 收口到 EXTERNALLY_DELIVERED")
    void firstBatchMovedToExternallyDelivered() {
        assertThat(MockAgentConfiguration.IMPLEMENTED_DOMAINS).isEmpty();
        assertThat(MockAgentConfiguration.EXTERNALLY_DELIVERED_DOMAINS).containsExactlyInAnyOrder(
                "account",
                "transfer",
                "creditcard_service",
                "wealth_aggregate",
                "fund_service",
                "insurance_service",
                "finance_assistant",
                "deposit_service",
                "loan_service",
                "payroll_service",
                "wealth_product");
        // 夹具类仍可独立实例化（供历史单测 / BaselineAgentContract）
        assertThat(new MockTransferAgent().techDomainCode()).isEqualTo("transfer");
        assertThat(new MockNavAgent().techDomainCode()).isEqualTo("finance_assistant");
    }

    @Test
    @DisplayName("脚手架覆盖其余科技域，不承接任何能力")
    void scaffoldsCoverRemainingDomains() {
        Set<String> scaffoldCodes = new HashSet<>();
        for (String code : MockAgentConfiguration.ALL_TECH_DOMAINS) {
            if (MockAgentConfiguration.IMPLEMENTED_DOMAINS.contains(code)
                    || MockAgentConfiguration.EXTERNALLY_DELIVERED_DOMAINS.contains(code)) {
                continue;
            }
            ScaffoldDomainAgent agent = new ScaffoldDomainAgent(code);
            scaffoldCodes.add(code);
            assertThat(agent.agentId()).isEqualTo("agent." + code);
            assertThat(agent.advertisedCapabilities()).isEmpty();
            assertThat(agent.supports("cap.anything")).isFalse();

            UnifiedTask task = new UnifiedTask(
                    "t-1", "trace-1", Enums.TaskSource.FAST_PATH, "noop", "cap.anything",
                    Map.of(), null, Map.of(), null, null, List.of(), null);
            TaskResult result = agent.execute(task);
            assertThat(result.status()).isEqualTo(Enums.TaskStatus.FAILED);
            assertThat(result.resultPayload()).containsEntry("error", "DOMAIN_NOT_OPEN:" + code);
        }
        assertThat(scaffoldCodes)
                .hasSize(MockAgentConfiguration.ALL_TECH_DOMAINS.size()
                        - MockAgentConfiguration.IMPLEMENTED_DOMAINS.size()
                        - MockAgentConfiguration.EXTERNALLY_DELIVERED_DOMAINS.size());
        assertThat(scaffoldCodes).contains("payment", "e_cny", "branch_service");
        assertThat(scaffoldCodes).doesNotContainAnyElementsOf(
                MockAgentConfiguration.EXTERNALLY_DELIVERED_DOMAINS);
    }

    @Test
    @DisplayName("附录 F 26 域被可执行 Mock、外部交付或脚手架完整覆盖")
    void allAppendixFDomainsCovered() {
        assertThat(MockAgentConfiguration.ALL_TECH_DOMAINS).hasSize(26);
        Set<String> covered = new HashSet<>(MockAgentConfiguration.IMPLEMENTED_DOMAINS);
        covered.addAll(MockAgentConfiguration.EXTERNALLY_DELIVERED_DOMAINS);
        for (String code : MockAgentConfiguration.ALL_TECH_DOMAINS) {
            if (!covered.contains(code)) {
                covered.add(code);
            }
        }
        assertThat(covered).containsExactlyInAnyOrderElementsOf(MockAgentConfiguration.ALL_TECH_DOMAINS);
        assertThat(MockAgentConfiguration.EXTERNALLY_DELIVERED_DOMAINS).hasSize(11);
    }
}
