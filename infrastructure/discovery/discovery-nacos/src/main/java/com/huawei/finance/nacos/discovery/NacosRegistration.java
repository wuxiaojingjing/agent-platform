package com.huawei.finance.nacos.discovery;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.huawei.finance.nacos.NacosProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把本进程注册进 Nacos，退出时摘掉。
 *
 * <h2>为什么一定要显式反注册</h2>
 *
 * <p>临时实例（ephemeral）本来就有心跳超时摘除，但那要等十几秒。发布期间这十几秒里，
 * 别的进程会持续把请求派到一个正在关闭的实例上——对于有副作用的金融操作，
 * 那不是「一次重试」而是「一批请求打在半关状态上」。所以关闭时主动摘除，
 * 让摘除发生在容器停止接收新请求之前。
 */
public class NacosRegistration implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NacosRegistration.class);

    private final NamingService naming;
    private final NacosProperties properties;
    private final Set<String> capabilities;
    private final String agentId;
    private final String implementationMode;

    private volatile Instance registered;

    public NacosRegistration(NamingService naming, NacosProperties properties, Set<String> capabilities) {
        this(naming, properties, capabilities, properties.getDiscovery().getServiceName(), "unknown");
    }

    public NacosRegistration(NamingService naming, NacosProperties properties, Set<String> capabilities,
                             String agentId, String implementationMode) {
        this.naming = naming;
        this.properties = properties;
        this.capabilities = new TreeSet<>(capabilities);
        this.agentId = agentId;
        this.implementationMode = implementationMode;
    }

    public void register(String ip, int port) {
        var discovery = properties.getDiscovery();
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setEphemeral(true);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(discovery.getCapabilityMetadataKey(), String.join(",", capabilities));
        metadata.put("huawei.finance.agent.id", agentId);
        metadata.put("huawei.finance.agent.implementation-mode", implementationMode);
        metadata.put("huawei.finance.agent.protocol-version", "a2a/2");
        metadata.put("huawei.finance.agent.scheme", "http");
        instance.setMetadata(metadata);
        try {
            naming.registerInstance(discovery.getServiceName(), discovery.getGroup(), instance);
            registered = instance;
            log.info("已注册到 Nacos service={} group={} 地址={}:{} 声称承接能力={}",
                    discovery.getServiceName(), discovery.getGroup(), ip, port,
                    capabilities.isEmpty() ? "（无，本进程不承接领域能力）" : capabilities);
        } catch (NacosException e) {
            // 注册失败不拦启动：注册中心是给别人找到我用的，找不到我不影响我自己办事。
            // 但要 ERROR，否则「服务起来了却没人能发现」会被当成偶发的调用失败去查
            log.error("注册到 Nacos 失败，本实例不会被其它进程发现：{}", e.getMessage());
        }
    }

    @Override
    public void close() {
        Instance instance = registered;
        if (instance == null) {
            return;
        }
        var discovery = properties.getDiscovery();
        try {
            naming.deregisterInstance(discovery.getServiceName(), discovery.getGroup(),
                    instance.getIp(), instance.getPort());
            log.info("已从 Nacos 摘除本实例 {}:{}", instance.getIp(), instance.getPort());
        } catch (NacosException e) {
            log.warn("从 Nacos 摘除本实例失败，将由心跳超时兜底（十几秒内仍可能被派单）：{}", e.getMessage());
        }
    }
}
