package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.Decision;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.ReasonCode;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.ShortCircuitLevel;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.intent.ComparePlans;
import com.huawei.finance.runtime.task.AgentTaskExecutor;
import com.huawei.finance.runtime.task.AgentTaskOutcome;
import com.huawei.finance.runtime.task.AgentTaskRequest;
import com.huawei.finance.registry.asset.AssetBundle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 跨域对比本轮联邦拉取（场景 4）。
 *
 * <p>与多意图「选一件再办」不同：两件都是只读查询，本轮直接取齐再渲染对照模板。
 * 每个只读子任务都经中控独立建档、护栏、幂等和落盘；对比本身不创建待办游标。
 */
@Component
public class ProductCompareFetcher {

    private static final Logger log = LoggerFactory.getLogger(ProductCompareFetcher.class);

    private static final String MISSING = "—";

    private final AgentTaskExecutor taskExecutor;

    public ProductCompareFetcher(AgentTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public boolean supports(IntentPlan plan) {
        return ComparePlans.isComparePlan(plan);
    }

    /**
     * @return 对比模板变量；任一腿失败返回 empty Map（调用方退回手递）
     */
    public Map<String, Object> fetch(RequestContext ctx, IntentPlan plan, AssetBundle bundle,
                                     ContextLease lease) {
        return fetch(ctx, plan, bundle::capability, lease);
    }

    Map<String, Object> fetch(RequestContext ctx, IntentPlan plan, CapabilityLookup capabilities,
                              ContextLease lease) {
        if (!supports(plan)) {
            return Map.of();
        }
        Map<String, Object> left = null;
        Map<String, Object> right = null;
        int index = 0;
        for (SubIntent item : plan.items()) {
            CapabilityCard card = capabilities.find(item.capabilityId());
            if (card == null) {
                log.warn("对比计划指向不存在的能力 {}", item.capabilityId());
                return Map.of();
            }
            if (card.riskLevel() != RiskLevel.R0) {
                log.error("对比计划包含非只读能力，拒绝在联邦对比中执行 capability={} risk={}",
                        card.capabilityId(), card.riskLevel());
                return Map.of();
            }
            TaskResult result = invokeRead(ctx, card, item, lease);
            if (result == null || !result.success()) {
                log.warn("对比联邦拉取失败 capability={} status={}",
                        item.capabilityId(), result == null ? null : result.status());
                return Map.of();
            }
            if (index++ == 0) {
                left = result.resultPayload();
            } else {
                right = result.resultPayload();
            }
        }
        if (left == null || right == null) {
            return Map.of();
        }
        return flatten(left, right);
    }

    @FunctionalInterface
    interface CapabilityLookup {
        CapabilityCard find(String capabilityId);
    }

    private TaskResult invokeRead(RequestContext ctx, CapabilityCard card, SubIntent item,
                                  ContextLease lease) {
        Map<String, Object> params = Map.of("productHint", item.text());
        RouteDecision decision = RouteDecision.builder()
                .decision(Decision.EXECUTE_CAPABILITY)
                .reasonCode(ReasonCode.HIGH_CONFIDENCE)
                .confidence(1.0)
                .candidateIds(List.of(card.capabilityId()))
                .configVersion("compare-plan")
                .shortCircuit(ShortCircuitLevel.NONE)
                .build();
        AgentTaskOutcome outcome = taskExecutor.execute(new AgentTaskRequest(
                ctx, decision, card, params, item.text(), false, List.of(), lease));
        return outcome.result();
    }

    private static Map<String, Object> flatten(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("leftName", str(left, "name"));
        slots.put("leftDomain", str(left, "domain"));
        slots.put("leftRiskLevel", str(left, "riskLevel"));
        slots.put("leftReturnRate", str(left, "returnRate"));
        slots.put("leftTerm", str(left, "term"));
        slots.put("rightName", str(right, "name"));
        slots.put("rightDomain", str(right, "domain"));
        slots.put("rightRiskLevel", str(right, "riskLevel"));
        slots.put("rightReturnRate", str(right, "returnRate"));
        slots.put("rightTerm", str(right, "term"));
        slots.put("compareReady", Boolean.TRUE);
        return slots;
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return MISSING;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? MISSING : text;
    }
}
