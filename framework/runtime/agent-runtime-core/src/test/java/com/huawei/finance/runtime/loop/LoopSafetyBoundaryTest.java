package com.huawei.finance.runtime.loop;

import static com.huawei.finance.orchestrator.loop.LoopContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.common.context.PrincipalState;
import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.*;
import com.huawei.finance.registry.asset.AssetBundle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoopSafetyBoundaryTest {
    private final LoopActionValidator validator = new LoopActionValidator();

    @Test
    void candidateRetrievalExcludesPrincipalProtectedCapabilitiesForAnonymousUsers() {
        CapabilityCard publicCard = card("cap.public", false, Map.of(), List.of());
        CapabilityCard protectedCard = card("cap.protected", true, Map.of(), List.of());
        AssetBundle assets = assets(publicCard, protectedCard);
        Run run = run(Map.of(), List.of("cap.public", "cap.protected"));
        LoopCandidateRetriever retriever = new LoopCandidateRetriever();

        RequestContext anonymous = context(PrincipalState.anonymous("TEST"));
        assertThat(retriever.retrieve(anonymous, run, assets, 8))
                .extracting(CapabilityCard::capabilityId)
                .containsExactly("cap.public");

        RequestContext verified = context(new PrincipalState("opaque-ref", true, "AUTHENTICATED", "TEST"));
        assertThat(retriever.retrieve(verified, run, assets, 8))
                .extracting(CapabilityCard::capabilityId)
                .containsExactly("cap.public", "cap.protected");
    }

    @Test
    void validatorRequiresParameterValueToMatchItsDeclaredSource() {
        CapabilityCard card = card("cap.transfer", true, Map.of(), List.of("amount"));
        Run run = run(Map.of("cap.balance", Map.of("amount", "100.00")), List.of(card.capabilityId()));
        LoopContext context = new LoopContext(run, Map.of("amount", "20.00"), null, List.of(card), 3);

        Action forged = action(card.capabilityId(), Map.of("amount", "999.00"),
                Map.of("amount", "CONFIRMED_SLOT"));
        assertThatThrownBy(() -> validator.validate(forged, List.of(card), context))
                .hasMessage("LOOP_CONFIRMED_SLOT_MISMATCH");

        Action fromFact = action(card.capabilityId(), Map.of("amount", "100.00"),
                Map.of("amount", "FACT:cap.balance"));
        assertThat(validator.validate(fromFact, List.of(card), context)).isSameAs(fromFact);

        Action missingFact = action(card.capabilityId(), Map.of("amount", "100.00"),
                Map.of("amount", "FACT:cap.unknown"));
        assertThatThrownBy(() -> validator.validate(missingFact, List.of(card), context))
                .hasMessage("LOOP_DEPENDENCY_FACT_MISSING");
    }

    @Test
    void validatorAppliesCapabilityInputSchemaBeforePolicyOrExecution() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("cardType"),
                "properties", Map.of("cardType", Map.of("type", "string",
                        "enum", List.of("信用卡", "借记卡"))));
        CapabilityCard card = card("cap.card.replace", true, inputSchema, List.of("cardType"));
        Run run = run(Map.of(), List.of(card.capabilityId()));
        LoopContext context = new LoopContext(run, Map.of("cardType", "储值卡"), null, List.of(card), 3);
        Action invalid = action(card.capabilityId(), Map.of("cardType", "储值卡"),
                Map.of("cardType", "CONFIRMED_SLOT"));

        assertThatThrownBy(() -> validator.validate(invalid, List.of(card), context))
                .hasMessage("LOOP_PARAMETER_SCHEMA_INVALID");
    }

    @Test
    void validatorRejectsAPlannerSuppliedFingerprintThatDoesNotMatchTheAction() {
        CapabilityCard card = card("cap.transfer", true, Map.of(), List.of("amount"));
        Run run = run(Map.of(), List.of(card.capabilityId()));
        LoopContext context = new LoopContext(run, Map.of("amount", "20.00"), null, List.of(card), 3);
        Action forged = new Action(ActionType.CALL_CAPABILITY, card.capabilityId(),
                Map.of("amount", "20.00"), Map.of("amount", "CONFIRMED_SLOT"),
                "NEXT", "model-controlled-value");

        assertThatThrownBy(() -> validator.validate(forged, List.of(card), context))
                .hasMessage("LOOP_ACTION_FINGERPRINT_INVALID");
    }

    private static Action action(String target, Map<String, Object> parameters, Map<String, String> provenance) {
        return new Action(ActionType.CALL_CAPABILITY, target, parameters, provenance, "NEXT",
                LoopActionFingerprint.of(ActionType.CALL_CAPABILITY, target, parameters));
    }

    private static RequestContext context(PrincipalState principal) {
        return new RequestContext("trace", "session", null, "tenant", "agent.test", "TEST", "", "",
                false, principal, null);
    }

    private static Run run(Map<String, Object> facts, List<String> candidates) {
        return new Run("tenant", "loop", "agent.test", "session", "root", "trace", "goal",
                Status.RUNNING, 0, 4, candidates, facts, null,
                Instant.now().plusSeconds(30), 0, Instant.now(), Instant.now());
    }

    private static CapabilityCard card(String id, boolean principalRequired,
                                       Map<String, Object> inputSchema, List<String> requiredSlots) {
        return new CapabilityCard(id, id, Enums.CapabilityType.TOOL, Enums.Granularity.TOOL,
                "agent.test", List.of("test"), "", List.of(), inputSchema, Map.of(), List.of(),
                List.of(), RiskLevel.R0, 1000, Enums.Idempotency.SUPPORTED, "owner", "1",
                Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), requiredSlots,
                Enums.GuardrailOwner.DOMAIN, principalRequired, ConfirmationPolicy.NONE, LoopAccess.DEFAULT);
    }

    private static AssetBundle assets(CapabilityCard... cards) {
        return new AssetBundle("v", "v", List.of(cards), List.of(), List.of(), null, null, null,
                Map.of(), Map.of(), null, null, null, null, null);
    }
}
