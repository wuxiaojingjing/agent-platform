package com.huawei.finance.runtime.bootstrap;

import com.huawei.finance.contracts.validation.ContractJson;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.orchestrator.loop.LoopContracts.*;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.runtime.loop.AgentLoopPlanner;
import com.huawei.finance.runtime.loop.FallbackAgentLoopPlanner;
import com.huawei.finance.runtime.loop.LoopActionFingerprint;
import com.huawei.finance.runtime.loop.LoopContext;
import com.huawei.finance.slowpath.DeepAgentSingleActionPlanner;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModelAgentLoopPlanner implements AgentLoopPlanner {
    private static final Logger log = LoggerFactory.getLogger(ModelAgentLoopPlanner.class);
    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties properties;
    private final AssetBundle assets;
    private final ContractValidator validator;
    private final AgentLoopPlanner fallback = new FallbackAgentLoopPlanner();
    private final DeepAgentSingleActionPlanner deepAgent;

    public ModelAgentLoopPlanner(ModelGatewayClient gateway, ModelGatewayProperties properties,
                                 AssetBundle assets, ContractValidator validator) {
        this(gateway, properties, assets, validator, new DeepAgentSingleActionPlanner());
    }

    ModelAgentLoopPlanner(ModelGatewayClient gateway, ModelGatewayProperties properties,
                          AssetBundle assets, ContractValidator validator,
                          DeepAgentSingleActionPlanner deepAgent) {
        this.gateway = gateway; this.properties = properties; this.assets = assets; this.validator = validator;
        this.deepAgent = deepAgent;
    }

    @Override public Action nextAction(LoopContext context) {
        if (!gateway.available()) return fallback.nextAction(context);
        var skill = assets.loopPlannerSkill();
        Map<String,String> vars = Map.of(
                "goal", context.run().goal(), "confirmedSlots", json(context.confirmedSlots()),
                "facts", json(context.run().facts()), "lastObservation", json(context.lastObservation()),
                "conversationHistory", json(context.conversationHistory()),
                "availableContext", json(context.availableContext()),
                "candidates", json(context.candidates()), "remainingBudget", String.valueOf(context.remainingIterations()));
        var config = properties.getLoop();
        DeepAgentSingleActionPlanner.Proposal proposal;
        try {
            proposal = deepAgent.propose(skill.getSystem(), skill.renderUser(vars), context.candidates(),
                    properties.resolveLogicalModel(config), config.getMaxTokens(), config.getTemperature(),
                    2, null, context.run().agentId(), context.run().loopId() + ':' + context.run().iteration())
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            log.warn("Loop Planner 模型调用失败，转入受限回退 stage=deep-agent type={}",
                    unavailable.getClass().getSimpleName());
            return fallback.nextAction(context);
        }
        if (proposal == null) {
            log.warn("Loop Planner 未产生唯一 Proposal，转入受限回退 stage=proposal-count");
            return fallback.nextAction(context);
        }
        String raw = json(proposal);
        var validation = validator.validateJson(SchemaRef.LOOP_ACTION_PROPOSAL, raw);
        if (!validation.valid()) {
            log.warn("Loop Planner Proposal 契约校验失败，转入受限回退 stage=schema reason={}",
                    validation.summary());
            return fallback.nextAction(context);
        }
        try {
            ActionType type = ActionType.valueOf(proposal.actionType());
            Map<String,Object> parameters = proposal.parameters();
            Map<String,String> provenance = proposal.inputProvenance();
            return new Action(type, proposal.targetId(), parameters, provenance, proposal.proposalReasonCode(),
                    LoopActionFingerprint.of(type, proposal.targetId(), parameters));
        } catch (Exception e) {
            log.warn("Loop Planner Proposal 转换失败，转入受限回退 stage=conversion type={}",
                    e.getClass().getSimpleName());
            return fallback.nextAction(context);
        }
    }

    private static String json(Object value) {
        try { return ContractJson.mapper().writeValueAsString(value); } catch (Exception e) { return "null"; }
    }
}
