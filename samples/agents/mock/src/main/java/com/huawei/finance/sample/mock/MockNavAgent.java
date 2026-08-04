package com.huawei.finance.sample.mock;

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
 * 菜单跳转 Mock 子 Agent（挂在金融助手域；历史父卡 {@code agent.nav}）。
 *
 * <p><b>保留供单测与覆盖率夹具</b>；运行时 Bean 已迁至
 * {@code com.huawei.finance.domain.finance.FinanceDomainAgent}（节点 id 为 {@code agent.finance_assistant}）。
 */
public class MockNavAgent implements TechDomainAgent {

    private static final Logger log = LoggerFactory.getLogger(MockNavAgent.class);

    private final Map<String, Map<String, Object>> meta;

    public MockNavAgent() {
        this.meta = loadMeta();
    }

    @Override
    public String techDomainCode() {
        return "finance_assistant";
    }

    @Override
    public String agentId() {
        return "agent.nav";
    }

    @Override
    public boolean supports(String capabilityId) {
        return capabilityId != null && capabilityId.startsWith("cap.nav.");
    }

    @Override
    public Set<String> advertisedCapabilities() {
        return Set.copyOf(meta.keySet());
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) {
            return MockAgents.missingIdempotency(task);
        }

        Map<String, Object> row = meta.getOrDefault(task.capabilityId(), Map.of());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menuId", String.valueOf(row.getOrDefault("menuId", task.capabilityId())));
        payload.put("menuName", String.valueOf(row.getOrDefault("menuName", "未知菜单")));
        payload.put("bksPath", String.valueOf(row.getOrDefault("bksPath", "")));
        payload.put("action", "OPEN_MENU");
        return MockAgents.success(task, payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> loadMeta() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        // 用 findInAssetRoots：菜单元数据缺了只是回显占位名，不拖挂启动
        Optional<Path> pathOpt = AgentAssetLocations.findInAssetRoots("menus/nav-meta.yaml");
        if (pathOpt.isEmpty()) {
            log.warn("未找到 menus/nav-meta.yaml，MockNavAgent 将回显占位菜单名");
            return Map.of();
        }
        Path path = pathOpt.get();
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> raw = yaml.readValue(in, Map.class);
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getKey().startsWith("cap.nav.") && e.getValue() instanceof Map<?, ?> m) {
                    out.put(e.getKey(), (Map<String, Object>) m);
                }
            }
            log.info("已加载菜单跳转元数据 {} 条 path={}", out.size(), path.toAbsolutePath());
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("读取导航元数据失败：" + path, e);
        }
    }
}
