package com.huawei.finance.nacos.discovery;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注册中心上有哪些智能体，各自承接什么能力。
 *
 * <p>能力清单来自实例元数据而不是服务名的命名约定：一个领域进程承接多个能力，
 * 服务名与能力是多对多的，靠名字反推迟早会错，而错的表现是派单到一个不办这件事的进程上。
 */
public class NacosAgentDirectory {

    private static final Logger log = LoggerFactory.getLogger(NacosAgentDirectory.class);

    private final NamingGateway naming;
    private final String capabilityMetadataKey;
    private final long cacheMs;

    private final AtomicLong roundRobin = new AtomicLong();

    private volatile List<AgentInstance> cached = List.of();
    private volatile long cachedAt = 0L;

    public NacosAgentDirectory(NamingGateway naming, String capabilityMetadataKey, long cacheMs) {
        this.naming = naming;
        this.capabilityMetadataKey = capabilityMetadataKey;
        this.cacheMs = cacheMs;
    }

    /**
     * 当前注册中心上的全部智能体实例。
     *
     * <p>列服务这一步是一次远端调用（列实例走客户端本地缓存），所以按 {@code cacheMs}
     * 缓存一小段。缓存过期时间就是「一个新领域进程上线后最迟多久会被派到单」，
     * 配大了上线要等，配 0 则每次派单都多一次网络往返。
     */
    public List<AgentInstance> agents() {
        long now = System.currentTimeMillis();
        List<AgentInstance> snapshot = cached;
        if (cacheMs > 0 && now - cachedAt < cacheMs) {
            return snapshot;
        }
        try {
            List<AgentInstance> fresh = new ArrayList<>();
            for (String service : naming.services()) {
                for (Instance instance : naming.instances(service)) {
                    fresh.add(toAgent(service, instance));
                }
            }
            cached = List.copyOf(fresh);
            cachedAt = now;
            return cached;
        } catch (NacosException e) {
            // 注册中心不可用时返回上一次的结果，而不是空表。返回空表等于把所有能力
            // 一次性判成「无人承接」，那会让注册中心的一次抖动直接变成全量业务失败；
            // 用旧地址至少还有连上的可能，连不上也只是单笔失败并按 RETRYABLE 处理
            log.warn("从 Nacos 取实例失败，沿用上一次的清单（{} 个实例）：{}", snapshot.size(), e.getMessage());
            return snapshot;
        }
    }

    /** 承接某能力的实例，健康的排在前面。 */
    public List<AgentInstance> instancesFor(String capabilityId) {
        List<AgentInstance> matched = new ArrayList<>();
        for (AgentInstance agent : agents()) {
            if (agent.capabilities().contains(capabilityId)) {
                matched.add(agent);
            }
        }
        matched.sort((a, b) -> Boolean.compare(b.healthy(), a.healthy()));
        return matched;
    }

    /**
     * 选一个健康实例的基址。
     *
     * <p>轮询而不是每次取第一个：取第一个会让同一个实例吃下全部流量，而另外几个实例
     * 只在它挂掉时才有机会——那时它们的连接池、JIT、缓存都是冷的。
     */
    public java.util.Optional<String> resolve(String capabilityId) {
        List<AgentInstance> healthy = instancesFor(capabilityId).stream()
                .filter(AgentInstance::healthy)
                .toList();
        if (healthy.isEmpty()) {
            return java.util.Optional.empty();
        }
        int index = (int) Math.floorMod(roundRobin.getAndIncrement(), healthy.size());
        return java.util.Optional.of(healthy.get(index).baseUrl());
    }

    /** 注册中心上所有健康实例声称承接的能力并集。 */
    public Set<String> knownCapabilities() {
        Set<String> all = new TreeSet<>();
        for (AgentInstance agent : agents()) {
            if (agent.healthy()) {
                all.addAll(agent.capabilities());
            }
        }
        return all;
    }

    private AgentInstance toAgent(String serviceName, Instance instance) {
        var metadata = instance.getMetadata() == null ? java.util.Map.<String, String>of() : instance.getMetadata();
        Set<String> capabilities = new LinkedHashSet<>(
                AgentInstance.parseCapabilities(metadata.get(capabilityMetadataKey)));
        String scheme = metadata.getOrDefault("huawei.finance.agent.scheme", "http");
        String baseUrl = scheme + "://" + instance.getIp() + ":" + instance.getPort();
        return new AgentInstance(
                serviceName,
                metadata.getOrDefault("huawei.finance.agent.id", serviceName),
                metadata.getOrDefault("huawei.finance.agent.implementation-mode", "unknown"),
                metadata.getOrDefault("huawei.finance.agent.protocol-version", "a2a/2"),
                baseUrl,
                instance.isHealthy() && instance.isEnabled(),
                capabilities);
    }
}
