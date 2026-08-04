package com.huawei.finance.slowpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.model.ConditionExpression;
import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.intent.ConditionResolver;
import com.huawei.finance.intent.SlowPathProperties;
import com.huawei.finance.oj.adapter.ModelGatewayClientFactory;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DeepAgent-backed compiler from natural language to the platform's constrained condition AST. */
public final class DeepAgentConditionResolver implements ConditionResolver {
    private static final Logger log = LoggerFactory.getLogger(DeepAgentConditionResolver.class);
    private static final String SYSTEM_PROMPT = """
            你是静态计划的条件编译器，不是执行器或决策器。
            你的唯一任务是把用户条件编译成业务无关的 JSON 条件表达式。
            只能引用输入中列出的 dependsOn 步骤、真实 facts、outputSchema 和 inputSchema。
            不得选择、追加、删除能力，不得直接回答 PROCEED、STOP，不得生成脚本、SQL、SpEL 或代码。
            表达式 operator 只能是 EQ、NE、GT、GTE、LT、LTE、AND、OR、NOT、EXISTS。
            operand source 只能是 STEP_OUTPUT、PARAMETER、LITERAL、EXPRESSION。
            STEP_OUTPUT 必须提供 stepId 和以 / 开头的 JSON Pointer；PARAMETER 必须提供 parameter；
            LITERAL 提供 literal；EXPRESSION 提供嵌套 expression。
            只有能够从给定 Schema 和事实可靠绑定时才调用提交工具；无法可靠编译时不要调用工具。
            """;

    private final SlowPathProperties properties;

    public DeepAgentConditionResolver(SlowPathProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<Resolution> resolve(Request request) {
        if (request == null || request.item() == null || request.item().planCondition() == null) {
            return Optional.empty();
        }
        List<String> submitted = new ArrayList<>(1);
        String scope = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Tool tool = new ExpressionProposalTool("propose_condition_expression_" + scope, submitted);
        RequestContext context = RequestContextHolder.get();
        String agentId = context == null ? RequestContext.AGENT_ENTRY : context.agentId();
        String sessionId = context == null ? "nosession" : context.sessionId();
        AgentSessionApi session = new AgentSessionApi("static-condition-" + request.planId()
                + '-' + request.item().stepId() + '-' + scope);
        DeepAgent agent = new DeepAgent(agentCard(), config(),
                ProcessScopedWorkspace.get(properties.getWorkspacePath(), agentId, sessionId));
        agent.registerHarnessTool(tool);
        try {
            agent.invoke(Map.of("query", prompt(request)), session);
        } catch (RuntimeException failure) {
            log.warn("Static Plan 条件延迟解析失败 plan={} step={} cause={}",
                    request.planId(), request.item().stepId(), failure.toString());
            return Optional.empty();
        } finally {
            agent.unregisterHarnessTool(tool);
        }
        if (submitted.size() != 1) return Optional.empty();
        try {
            ConditionExpression expression = ContractJson.mapper().readValue(
                    submitted.getFirst(), ConditionExpression.class);
            return Optional.of(new Resolution(expression, properties.getModel(), "static-condition-v1"));
        } catch (Exception invalid) {
            log.warn("Static Plan 条件表达式不是合法契约 plan={} step={} cause={}",
                    request.planId(), request.item().stepId(), invalid.toString());
            return Optional.empty();
        }
    }

    private DeepAgentConfig config() {
        return DeepAgentConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .maxIterations(Math.max(1, Math.min(3, properties.getMaxIterations())))
                .enableTaskLoop(true)
                .enableTaskPlanning(false)
                .addGeneralPurposeAgent(false)
                .enableAsyncSubagent(false)
                .model(ModelRequestConfig.builder().modelName(properties.getModel())
                        .maxTokens(768).temperature(0.0).build())
                .backend(ModelGatewayClientFactory.clientConfig())
                .build();
    }

    private static String prompt(Request request) {
        Map<String, Object> dependencySchemas = new java.util.LinkedHashMap<>();
        request.plan().items().stream()
                .filter(step -> request.item().dependsOn().contains(step.stepId()))
                .forEach(step -> {
                    var card = request.cards().get(step.capabilityId());
                    dependencySchemas.put(step.stepId(), card == null ? Map.of() : card.outputSchema());
                });
        var current = request.cards().get(request.item().capabilityId());
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("conditionText", request.item().planCondition().originalText());
        input.put("dependsOn", request.item().dependsOn());
        input.put("facts", request.stepFacts());
        input.put("dependencyOutputSchemas", dependencySchemas);
        input.put("parameters", request.parameters());
        input.put("currentInputSchema", current == null ? Map.of() : current.inputSchema());
        try {
            return ContractJson.mapper().writeValueAsString(input);
        } catch (Exception impossible) {
            throw new IllegalStateException("CONDITION_RESOLUTION_INPUT_SERIALIZATION_FAILED", impossible);
        }
    }

    private static AgentCard agentCard() {
        AgentCard card = new AgentCard();
        card.setName("agent-platform-static-condition-compiler");
        card.setDescription("把延迟条件编译为受控表达式，不执行能力、不决定是否放行");
        return card;
    }

    static final class ExpressionProposalTool extends Tool {
        private final List<String> sink;

        ExpressionProposalTool(String name, List<String> sink) {
            super(ToolCard.builder().id(name).name(name)
                    .description("提交一份通用条件表达式 JSON；只记录提案，不执行或判断条件")
                    .inputParams(Map.of(
                            "type", "object",
                            "additionalProperties", false,
                            "required", List.of("expressionJson"),
                            "properties", Map.of("expressionJson", Map.of(
                                    "type", "string", "minLength", 2))))
                    .build());
            this.sink = sink;
        }

        @Override
        public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
            if (!sink.isEmpty()) throw new IllegalStateException("MULTIPLE_CONDITION_EXPRESSIONS_PROPOSED");
            Object raw = inputs == null ? null : inputs.get("expressionJson");
            if (raw == null || String.valueOf(raw).isBlank()) {
                throw new IllegalArgumentException("CONDITION_EXPRESSION_MISSING");
            }
            sink.add(String.valueOf(raw));
            return Map.of("accepted", true, "note", "表达式已记录但尚未求值或执行");
        }

        @Override
        public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("Condition Proposal Tool 没有流式结果");
        }
    }
}
