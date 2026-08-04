package com.huawei.finance.product.mobilebanking.api;

import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.contracts.port.TechDomainAgent;
import com.huawei.finance.nacos.discovery.AgentInstance;
import com.huawei.finance.nacos.discovery.NacosAgentDirectory;
import com.huawei.finance.registry.asset.AssetStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「现在到底有哪些智能体，各自能办什么」。
 *
 * <p>这个问题此前只能靠翻日志与翻配置回答，而两者都会过期。分两段列而不是合成一张表，
 * 是因为它们的失效方式不同：进程内的 Agent 装上了就一定在，注册中心上的随时可能下线，
 * 合成一张表会让「这个能力还有没有人办」变得看不出来。
 *
 * <p>能力清单取自 {@code advertisedCapabilities}，而派单真值是 {@code supports}。
 * 两者可以不一致（动态发现下清单本就是快照），所以这里明说清单是「声称」。
 */
@RestController
@RequestMapping("/internal")
public class AgentDirectoryController {

    private final List<DomainAgent> inProcess;
    private final ObjectProvider<NacosAgentDirectory> directory;
    private final ObjectProvider<AssetStore> assets;

    public AgentDirectoryController(
            List<DomainAgent> inProcess,
            ObjectProvider<NacosAgentDirectory> directory,
            ObjectProvider<AssetStore> assets) {
        this.inProcess = inProcess;
        this.directory = directory;
        this.assets = assets;
    }

    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<Map<String, Object>> local = new ArrayList<>();
        for (DomainAgent agent : inProcess) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent", agent.getClass().getSimpleName());
            if (agent instanceof TechDomainAgent tech) {
                row.put("agentId", tech.agentId());
                row.put("techDomain", tech.techDomainCode());
            }
            row.put("capabilities", new TreeSet<>(agent.advertisedCapabilities()));
            local.add(row);
        }

        NacosAgentDirectory registry = directory.getIfAvailable();
        Map<String, Object> remote = registry == null
                ? Map.of("enabled", false)
                : Map.of("enabled", true, "instances", describe(registry.agents()));

        AssetStore store = assets.getIfAvailable();
        List<ConfiguredAgentCatalog.ConfiguredAgent> configured = store == null
                ? List.of()
                : ConfiguredAgentCatalog.load(store.root(), store.current().capabilities());

        List<AgentInstance> instances = registry == null ? List.of() : registry.agents();
        List<Map<String, Object>> unified = unified(configured, instances);
        long online = unified.stream().filter(row -> "ONLINE".equals(row.get("runtimeStatus"))).count();
        long unhealthy = unified.stream().filter(row -> "UNHEALTHY".equals(row.get("runtimeStatus"))).count();
        long scaffold = unified.stream().filter(row -> "SCAFFOLD".equals(row.get("implementationStatus"))).count();
        Map<String, Object> summary = Map.of(
                "configured", unified.size(),
                "online", online,
                "unhealthy", unhealthy,
                "offline", unified.size() - online - unhealthy,
                "scaffold", scaffold,
                "implemented", unified.size() - scaffold);
        return Map.of("configured", configured, "inProcess", local, "registry", remote,
                "agents", unified, "summary", summary);
    }

    private static List<Map<String, Object>> unified(
            List<ConfiguredAgentCatalog.ConfiguredAgent> configured, List<AgentInstance> instances) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConfiguredAgentCatalog.ConfiguredAgent agent : configured) {
            List<AgentInstance> matched = instances.stream()
                    .filter(instance -> agent.agentId().equals(instance.agentId()))
                    .toList();
            String runtimeStatus = matched.stream().anyMatch(AgentInstance::healthy)
                    ? "ONLINE" : matched.isEmpty() ? "OFFLINE" : "UNHEALTHY";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", agent.agentId());
            row.put("displayName", agent.displayName());
            row.put("roles", agent.roles());
            row.put("domains", agent.domains());
            row.put("implementationMode", agent.implementationMode());
            row.put("implementationStatus",
                    "scaffold".equals(agent.implementationMode()) ? "SCAFFOLD" : "IMPLEMENTED");
            row.put("runtimeStatus", runtimeStatus);
            row.put("capabilities", agent.capabilities());
            row.put("instances", matched.size());
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> describe(List<AgentInstance> instances) {
        List<Map<String, Object>> described = new ArrayList<>();
        for (AgentInstance instance : instances) {
            described.add(Map.of(
                    "serviceName", instance.serviceName(),
                    "agentId", instance.agentId(),
                    "implementationMode", instance.implementationMode(),
                    "protocolVersion", instance.protocolVersion(),
                    "baseUrl", instance.baseUrl(),
                    "healthy", instance.healthy(),
                    "capabilities", new TreeSet<>(instance.capabilities())));
        }
        return described;
    }
}
