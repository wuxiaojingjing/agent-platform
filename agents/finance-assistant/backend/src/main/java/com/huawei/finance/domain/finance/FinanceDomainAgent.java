package com.huawei.finance.domain.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.huawei.finance.registry.asset.AgentAssetLocations;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 金融助手域叶子：菜单跳转（阶段 3b；自 MockNavAgent 上收）。
 *
 * <p>节点卡为 {@code agent.finance_assistant}；历史父卡 {@code agent.nav} 归并为本节点本地能力
 *（见架构草案 v0.4），不再单独占路由表席位。
 */
public class FinanceDomainAgent implements TechDomainAgent {

    private static final Logger log = LoggerFactory.getLogger(FinanceDomainAgent.class);

    private final NavigationCatalogPort catalog;

    public FinanceDomainAgent(NavigationCatalogPort catalog) {
        this.catalog = catalog;
    }

    @Override
    public String techDomainCode() {
        return "finance_assistant";
    }

    @Override
    public String agentId() {
        return "agent.finance_assistant";
    }

    @Override
    public boolean supports(String capabilityId) {
        return capabilityId != null && capabilityId.startsWith("cap.nav.");
    }

    @Override
    public Set<String> advertisedCapabilities() {
        return catalog.capabilities();
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) {
            return DomainAgents.missingIdempotency(task);
        }
        Map<String, Object> row = catalog.find(task.capabilityId());
        if (row.isEmpty()) {
            return new TaskResult(task.taskId(), com.huawei.finance.contracts.model.Enums.TaskStatus.FAILED,
                    com.huawei.finance.contracts.model.Enums.FailureClass.FATAL,
                    Map.of("reasonCode", "NAVIGATION_NOT_FOUND"), task.idempotencyKey(), task.guardrailCheck());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menuId", String.valueOf(row.getOrDefault("menuId", task.capabilityId())));
        payload.put("menuName", String.valueOf(row.getOrDefault("menuName", "未知菜单")));
        payload.put("bksPath", String.valueOf(row.getOrDefault("bksPath", "")));
        payload.put("action", "OPEN_MENU");
        return DomainAgents.success(task, payload);
    }

}
