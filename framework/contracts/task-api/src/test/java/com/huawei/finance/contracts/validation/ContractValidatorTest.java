package com.huawei.finance.contracts.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ConfirmationPolicy;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.EffectiveLoopAccess;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.LoopAccess;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.model.XiaoiExternalEvidence;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContractValidatorTest {

    private final ContractValidator validator = new ContractValidator();

    @Test
    @DisplayName("所有 Schema 都能编译，构造函数不抛异常即通过")
    void allSchemasCompile() {
        for (SchemaRef ref : SchemaRef.values()) {
            assertTrue(validator.validateJson(ref, "{}") != null, ref.name());
        }
    }

    @Test
    @DisplayName("小 i 外部证据保留原始分与会话引用，并按结果类型校验")
    void xiaoiExternalEvidenceContract() {
        var evidence = new XiaoiExternalEvidence(
                XiaoiExternalEvidence.CallStatus.SUCCEEDED,
                XiaoiExternalEvidence.MatchStatus.MATCHED,
                XiaoiExternalEvidence.ResultType.KNOWLEDGE,
                "knowledge-26023", 0.87, Map.of("channel", "mobile"),
                List.of("cap.nav.transfer_快速转账"), "legacy-session-1", "xiaoi-8.0");

        assertTrue(validator.validate(SchemaRef.XIAOI_EXTERNAL_EVIDENCE, evidence).valid());
        assertFalse(validator.validateJson(SchemaRef.XIAOI_EXTERNAL_EVIDENCE, """
                {"callStatus":"SUCCEEDED","matchStatus":"MATCHED","resultType":"KNOWLEDGE",
                 "dimension":{},"actionRefs":[],"sourceVersion":"xiaoi-8.0"}
                """).valid());
    }

    @Test
    @DisplayName("Decision 一次性使用完整路由词表，不得重新引入旧四出口")
    void decisionVocabularyIsComplete() {
        assertEquals(Set.of("DIRECT_KNOWLEDGE", "NAVIGATION", "EXECUTE_CAPABILITY", "START_WORKFLOW",
                        "STATIC_PLAN", "DELEGATE_GOAL", "START_LOOP", "RESUME_TASK", "RESUME_LOOP",
                        "CLARIFY", "CANCEL", "REJECT", "HANDOFF"),
                java.util.Arrays.stream(Decision.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    @DisplayName("能力卡确认与 Loop 默认值按风险收紧，R2 不允许放宽")
    void capabilityDefaultsAndIllegalCombinations() {
        CapabilityCard r0 = card(RiskLevel.R0, null, null);
        assertEquals(ConfirmationPolicy.NONE, r0.confirmationPolicy());
        assertEquals(LoopAccess.DEFAULT, r0.loopAccess());
        assertEquals(EffectiveLoopAccess.AUTO_READ_ONLY, r0.effectiveLoopAccess());

        CapabilityCard r1 = card(RiskLevel.R1, null, null);
        assertEquals(ConfirmationPolicy.EXPLICIT, r1.confirmationPolicy());
        assertEquals(EffectiveLoopAccess.PROPOSE_ONLY, r1.effectiveLoopAccess());
        assertEquals(ConfirmationPolicy.REVIEW_ONLY,
                card(RiskLevel.R1, ConfirmationPolicy.REVIEW_ONLY, null).confirmationPolicy());

        assertEquals(ConfirmationPolicy.EXPLICIT,
                card(RiskLevel.R2, null, null).confirmationPolicy());
        assertEquals(EffectiveLoopAccess.DENY,
                card(RiskLevel.R0, null, LoopAccess.DENY).effectiveLoopAccess());
        assertThrows(IllegalArgumentException.class,
                () -> card(RiskLevel.R1, ConfirmationPolicy.NONE, null));
        assertThrows(IllegalArgumentException.class,
                () -> card(RiskLevel.R2, ConfirmationPolicy.REVIEW_ONLY, null));
    }

    @Test
    @DisplayName("EXECUTE_CAPABILITY 必须选出且只选出一个候选")
    void fastExecuteRequiresExactlyOneCandidate() {
        RouteDecision noCandidate = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .confidence(0.9)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .build();
        assertFalse(validator.validate(SchemaRef.ROUTE_DECISION, noCandidate).valid());

        RouteDecision twoCandidates = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .candidateIds(List.of("cap.a", "cap.b"))
                .confidence(0.9)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .build();
        assertFalse(validator.validate(SchemaRef.ROUTE_DECISION, twoCandidates).valid());

        RouteDecision ok = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .candidateIds(List.of("cap.a"))
                .confidence(0.9)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .build();
        assertTrue(validator.validate(SchemaRef.ROUTE_DECISION, ok).valid());
    }

    @Test
    @DisplayName("CLARIFY 必须说明缺哪个槽位")
    void clarifyRequiresMissingSlots() {
        RouteDecision bad = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .confidence(0.6)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .build();
        assertFalse(validator.validate(SchemaRef.ROUTE_DECISION, bad).valid());

        RouteDecision ok = RouteDecision.builder()
                .decision(Decision.CLARIFY)
                .confidence(0.6)
                .reasonCode(ReasonCode.MISSING_SLOT)
                .missingSlots(List.of("cardType"))
                .build();
        assertTrue(validator.validate(SchemaRef.ROUTE_DECISION, ok).valid());
    }

    @Test
    @DisplayName("护栏未通过时不允许携带幂等键，构造期就要拦住")
    void idempotencyKeyRequiresPassedGuardrail() {
        assertThrows(IllegalArgumentException.class, () -> new UnifiedTask(
                "t1", "tr1", Enums.TaskSource.FAST_PATH, "转账", "cap.transfer",
                Map.of(), RiskLevel.R2, Map.of(), GuardrailCheck.pending(),
                "idem-key-1", List.of(), null));
    }

    @Test
    @DisplayName("护栏未通过时不允许携带幂等键，Schema 侧同样拦住")
    void idempotencyKeyRuleAlsoEnforcedBySchema() {
        String json = """
                {
                  "taskId": "t1", "traceId": "tr1", "source": "FAST_PATH",
                  "capabilityId": "cap.transfer", "riskLevel": "R2",
                  "guardrailCheck": {"status": "PENDING"},
                  "idempotencyKey": "idem-key-1"
                }
                """;
        assertFalse(validator.validateJson(SchemaRef.UNIFIED_TASK, json).valid());
    }

    @Test
    @DisplayName("意图路径和 A2A 调用来源是两个独立合法字段")
    void intentPathAndInvocationOriginAreIndependent() {
        UnifiedTask task = new UnifiedTask(
                "target-task", "trace", Enums.TaskSource.SLOW_PATH,
                Enums.InvocationOrigin.A2A, "查询余额", "cap.account.balance.query",
                Map.of(), RiskLevel.R0, Map.of(), GuardrailCheck.passed(),
                "delegation-1", List.of(), null);

        assertTrue(validator.validate(SchemaRef.UNIFIED_TASK, task).valid());
        assertFalse(validator.validateJson(SchemaRef.UNIFIED_TASK, """
                {
                  "taskId":"target-task", "traceId":"trace", "source":"A2A",
                  "invocationOrigin":"A2A", "capabilityId":"cap.account.balance.query",
                  "riskLevel":"R0", "guardrailCheck":{"status":"PASSED"}
                }
                """).valid());
    }

    @Test
    @DisplayName("模型仲裁输出：枚举越界、单能力空候选和额外字段都判非法")
    void modelOutputValidation() {
        assertFalse(validator.validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT,
                """
                {"decision": "JUST_DO_IT", "confidence": 0.9, "reasonCode": "HIGH_CONFIDENCE"}
                """).valid());

        assertFalse(validator.validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT,
                """
                {"decision": "EXECUTE_CAPABILITY", "taskShape":"SINGLE_ACTION", "candidateIds":[],
                 "confidence": 1.7, "reasonCode": "HIGH_CONFIDENCE"}
                """).valid());

        assertFalse(validator.validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT,
                """
                {"decision": "EXECUTE_CAPABILITY", "taskShape":"SINGLE_ACTION",
                 "candidateIds": ["cap.balance.query"],
                 "confidence": 0.93, "reasonCode": "HIGH_CONFIDENCE", "reason": "用户直接询问余额"}
                """).valid());

        assertTrue(validator.validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT,
                """
                {"decision": "EXECUTE_CAPABILITY", "taskShape":"SINGLE_ACTION",
                 "candidateIds": ["cap.balance.query"], "missingSlots": [], "extractedSlots": {},
                 "confidence": 0.93, "reasonCode": "HIGH_CONFIDENCE"}
                """).valid());
    }

    @Test
    @DisplayName("Loop 模型参数来源只允许已确认槽位或已提交事实")
    void loopActionProvenanceVocabularyIsClosed() {
        assertTrue(validator.validateJson(SchemaRef.LOOP_ACTION_PROPOSAL, """
                {"actionType":"CALL_CAPABILITY","targetId":"cap.x","parameters":{"amount":"10"},
                 "inputProvenance":{"amount":"FACT:cap.balance"},"proposalReasonCode":"NEXT"}
                """).valid());
        assertFalse(validator.validateJson(SchemaRef.LOOP_ACTION_PROPOSAL, """
                {"actionType":"CALL_CAPABILITY","targetId":"cap.x","parameters":{"amount":"10"},
                 "inputProvenance":{"amount":"MODEL_GUESS"},"proposalReasonCode":"NEXT"}
                """).valid());
    }

    @Test
    @DisplayName("能力卡内嵌 Schema 可直接校验运行时参数")
    void embeddedCapabilitySchemaValidation() {
        Map<String, Object> schema = Map.of("type", "object", "required", List.of("cardType"),
                "properties", Map.of("cardType", Map.of("enum", List.of("信用卡", "借记卡"))));
        assertTrue(validator.validateSchemaDefinition(schema).valid());
        assertTrue(validator.validateSchema(schema, Map.of("cardType", "信用卡")).valid());
        assertFalse(validator.validateSchema(schema, Map.of("cardType", "储值卡")).valid());
    }

    @Test
    @DisplayName("R2 能力卡必须声明 idempotency=REQUIRED")
    void r2CapabilityMustRequireIdempotency() {
        String json = """
                {
                  "capabilityId": "cap.transfer", "name": "转账", "type": "TOOL",
                  "granularity": "TOOL", "parentCapabilityId": "agent.payment",
                  "domains": ["payment"], "riskLevel": "R2", "version": "1.0.0",
                  "status": "ACTIVE", "idempotency": "SUPPORTED"
                }
                """;
        assertFalse(validator.validateJson(SchemaRef.CAPABILITY_CARD, json).valid());
    }

    @Test
    @DisplayName("TOOL 类能力卡必须挂在某个 Agent 下")
    void toolMustDeclareParentAgent() {
        String json = """
                {
                  "capabilityId": "cap.balance.query", "name": "查询余额", "type": "TOOL",
                  "granularity": "TOOL", "domains": ["account"], "riskLevel": "R0",
                  "version": "1.0.0", "status": "ACTIVE", "idempotency": "SUPPORTED"
                }
                """;
        assertFalse(validator.validateJson(SchemaRef.CAPABILITY_CARD, json).valid());
    }

    private static CapabilityCard card(RiskLevel risk, ConfirmationPolicy confirmation, LoopAccess loopAccess) {
        return new CapabilityCard("cap.test." + risk.name().toLowerCase(), "测试能力",
                Enums.CapabilityType.TOOL, Enums.Granularity.TOOL, "agent.test", List.of("test"),
                "测试确认策略。本能力不处理其他业务", List.of("测试"), Map.of(), Map.of(),
                List.of(), List.of(), risk, 1000,
                risk == RiskLevel.R2 ? Enums.Idempotency.REQUIRED : Enums.Idempotency.SUPPORTED,
                "test", "1.0.0", Enums.CapabilityStatus.ACTIVE, List.of(), List.of(), List.of(),
                Enums.GuardrailOwner.DOMAIN, true, confirmation, loopAccess);
    }
}
