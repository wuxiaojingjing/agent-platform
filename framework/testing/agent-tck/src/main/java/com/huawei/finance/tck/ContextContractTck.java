package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.a2a.AgentNode;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationMode;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.PrincipalContext;
import com.huawei.finance.contracts.model.ContextEvidence;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.SubtaskContextEnvelope;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Language-neutral a2a/2 context checks backed by the published JSON Schemas. */
public final class ContextContractTck {
    private ContextContractTck() { }

    public static void assertContextCapableTask(
            AgentNode node, String capabilityId, Map<String, Object> parameters) {
        ContextEvidence fact = new ContextEvidence("fact:tck:account",
                ContextEvidence.Kind.TOOL_FACT, Map.of("accountRef", "opaque-ref"),
                "agent.tck.source", "task-source", null, Instant.now(), null,
                ContextEvidence.Sensitivity.SENSITIVE);
        SubtaskContextEnvelope context = new SubtaskContextEnvelope("lease-tck", 3,
                Instant.now().plusSeconds(30), "TCK context task", Map.of(), List.of(fact),
                List.of(capabilityId), List.of(SubtaskContextEnvelope.Scope.SUBTASK),
                SubtaskContextEnvelope.Scope.SUBTASK);
        ContractValidator validator = new ContractValidator();
        validator.validate(SchemaRef.SUBTASK_CONTEXT_ENVELOPE, context)
                .orThrow("SubtaskContextEnvelope");

        DelegationEnvelope envelope = new DelegationEnvelope(
                DelegationEnvelope.CURRENT_VERSION, "tck-tenant", "agent.tck.source",
                node.agentId(), "root-tck-context", "parent-tck", "source-tck",
                "delegation-tck-context", "trace-tck-context",
                PrincipalContext.anonymous("TCK", "session:tck"), DelegationMode.TASK,
                Enums.TaskSource.FAST_PATH, null, capabilityId,
                parameters == null ? Map.of() : parameters, List.of(),
                Instant.now().plusSeconds(30), List.of("agent.tck.source"), context);
        var receipt = node.handle(envelope);

        assertThat(receipt).isNotNull();
        if (receipt.outcome() == DelegationOutcome.SUCCEEDED
                || receipt.outcome() == DelegationOutcome.NEED_USER
                || receipt.outcome() == DelegationOutcome.PARTIAL) {
            assertThat(receipt.contextDelta())
                    .as("context-capable a2a/2 nodes must return ContextDelta")
                    .isNotNull();
            validator.validate(SchemaRef.CONTEXT_DELTA, receipt.contextDelta())
                    .orThrow("ContextDelta");
            assertThat(receipt.contextDelta().baseStateVersion()).isEqualTo(3);
            assertThat(receipt.contextDelta().upserts())
                    .noneMatch(item -> item.kind() == ContextEvidence.Kind.USER_TURN);
        }
    }
}
