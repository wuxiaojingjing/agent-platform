package com.huawei.finance.slowpath;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.oj.adapter.ModelGatewayClientFactory;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Runs one DeepAgent planning turn with proposal-only tools. */
public class DeepAgentSingleActionPlanner {
    public record Proposal(String actionType, String targetId, Map<String,Object> parameters,
                           Map<String,String> inputProvenance, String proposalReasonCode) {
        public Proposal {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            inputProvenance = inputProvenance == null ? Map.of() : Map.copyOf(inputProvenance);
        }
    }

    public Optional<Proposal> propose(String systemPrompt, String userPrompt, List<CapabilityCard> candidates,
                                      String modelName, int maxTokens, double temperature, int maxIterations,
                                      String workspacePath, String agentId, String sessionId) {
        List<Proposal> proposals = new ArrayList<>(1);
        AgentSessionApi session = new AgentSessionApi("loop-planner-" + sessionId);
        Workspace workspace = ProcessScopedWorkspace.get(workspacePath, agentId, sessionId);
        DeepAgent agent = new DeepAgent(agentCard(), config(systemPrompt, modelName, maxTokens,
                temperature, maxIterations), workspace);
        List<Tool> registered = new ArrayList<>();
        String toolScope = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        register(agent, registered, proposals, scoped("propose_knowledge_search", toolScope),
                "SEARCH_KNOWLEDGE", List.of());
        register(agent, registered, proposals, scoped("propose_ask_user", toolScope), "ASK_USER", List.of());
        register(agent, registered, proposals, scoped("propose_finish", toolScope), "FINISH", List.of());
        register(agent, registered, proposals, scoped("propose_handoff", toolScope), "HANDOFF", List.of());
        register(agent, registered, proposals, scoped("propose_menu_resolution", toolScope), "RESOLVE_MENU",
                targets(candidates, card -> card.capabilityId().startsWith("cap.nav.")));
        register(agent, registered, proposals, scoped("propose_agent_goal", toolScope), "DELEGATE_GOAL",
                targets(candidates, card -> card.type() == Enums.CapabilityType.AGENT));
        register(agent, registered, proposals, scoped("propose_capability", toolScope), "CALL_CAPABILITY",
                targets(candidates, card -> card.type() != Enums.CapabilityType.AGENT
                        && !card.capabilityId().startsWith("cap.nav.")));

        try {
            agent.invoke(Map.of("query", userPrompt), session);
        } finally {
            registered.reversed().forEach(agent::unregisterHarnessTool);
        }
        return proposals.size() == 1 ? Optional.of(proposals.getFirst()) : Optional.empty();
    }

    private static String scoped(String base, String scope) {
        return base + '_' + scope;
    }

    private static com.openjiuwen.core.singleagent.schema.AgentCard agentCard() {
        var card = new com.openjiuwen.core.singleagent.schema.AgentCard();
        card.setName("agent-platform-loop-planner");
        card.setDescription("提出一个受控 Loop 动作，不执行任何业务能力");
        return card;
    }

    private static DeepAgentConfig config(String systemPrompt, String modelName, int maxTokens,
                                          double temperature, int maxIterations) {
        return DeepAgentConfig.builder()
                .systemPrompt(systemPrompt)
                .maxIterations(Math.max(1, maxIterations))
                .enableTaskLoop(true)
                .enableTaskPlanning(false)
                .addGeneralPurposeAgent(false)
                .enableAsyncSubagent(false)
                .model(ModelRequestConfig.builder().modelName(modelName)
                        .maxTokens(Math.max(1, maxTokens)).temperature(temperature).build())
                .backend(ModelGatewayClientFactory.clientConfig())
                .build();
    }

    private static List<String> targets(List<CapabilityCard> cards,
                                        java.util.function.Predicate<CapabilityCard> predicate) {
        return cards.stream().filter(predicate).map(CapabilityCard::capabilityId).toList();
    }

    private static void register(DeepAgent agent, List<Tool> registered, List<Proposal> proposals,
                                 String toolName, String actionType, List<String> targets) {
        if (isTargeted(actionType) && targets.isEmpty()) return;
        Tool tool = new ProposalActionTool(toolName, actionType, targets, proposals);
        agent.registerHarnessTool(tool);
        registered.add(tool);
    }

    private static boolean isTargeted(String actionType) {
        return "RESOLVE_MENU".equals(actionType) || "CALL_CAPABILITY".equals(actionType)
                || "DELEGATE_GOAL".equals(actionType);
    }

    static final class ProposalActionTool extends Tool {
        private final String actionType;
        private final List<String> allowedTargets;
        private final List<Proposal> sink;

        ProposalActionTool(String name, String actionType, List<String> targets, List<Proposal> sink) {
            super(ToolCard.builder().id(name).name(name)
                    .description("只提出一个 " + actionType + " 动作，不执行能力、不生成面客回复")
                    .inputParams(schema(actionType, targets)).build());
            this.actionType = actionType;
            this.allowedTargets = List.copyOf(targets);
            this.sink = sink;
        }

        @Override public Object invoke(Map<String,Object> inputs, Map<String,Object> kwargs) {
            if (!sink.isEmpty()) throw new IllegalStateException("MULTIPLE_ACTIONS_PROPOSED");
            Map<String,Object> safe = inputs == null ? Map.of() : inputs;
            String target = isTargeted(actionType) ? text(safe.get("targetId")) : null;
            if (isTargeted(actionType) && !allowedTargets.contains(target)) {
                throw new IllegalArgumentException("TARGET_OUTSIDE_ALLOWED_CANDIDATES");
            }
            Map<String,Object> parameters = isTargeted(actionType)
                    ? objectMap(safe.get("parameters")) : Map.of();
            Map<String,String> provenance = isTargeted(actionType)
                    ? stringMap(safe.get("inputProvenance")) : Map.of();
            String reason = text(safe.get("proposalReasonCode"));
            sink.add(new Proposal(actionType, target, parameters, provenance, reason));
            return Map.of("accepted", true, "note", "动作已提议但尚未执行");
        }

        @Override public Iterator<Object> stream(Map<String,Object> inputs, Map<String,Object> kwargs) {
            throw new UnsupportedOperationException("Proposal Tool 没有流式结果");
        }

        private static Map<String,Object> schema(String actionType, List<String> targets) {
            Map<String,Object> properties = new LinkedHashMap<>();
            if (isTargeted(actionType)) {
                properties.put("targetId", Map.of("type", "string", "enum", targets));
            }
            properties.put("parameters", Map.of("type", "object"));
            properties.put("inputProvenance", Map.of("type", "object", "additionalProperties",
                    Map.of("type", "string", "pattern", "^(CONFIRMED_SLOT|FACT:[A-Za-z0-9._:-]+)$")));
            properties.put("proposalReasonCode", Map.of("type", "string", "minLength", 1));
            List<String> required = isTargeted(actionType)
                    ? List.of("targetId", "parameters", "inputProvenance", "proposalReasonCode")
                    : List.of("parameters", "inputProvenance", "proposalReasonCode");
            return Map.of("type", "object", "additionalProperties", false,
                    "properties", properties, "required", required);
        }

        @SuppressWarnings("unchecked")
        private static Map<String,Object> objectMap(Object value) {
            return value instanceof Map<?,?> map ? Map.copyOf((Map<String,Object>) map) : Map.of();
        }
        private static Map<String,String> stringMap(Object value) {
            if (!(value instanceof Map<?,?> map)) return Map.of();
            Map<String,String> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
            return Map.copyOf(result);
        }
        private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    }
}
