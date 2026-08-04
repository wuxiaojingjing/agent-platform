package com.huawei.finance.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.nacos.config.NacosConfigRefresher;
import com.huawei.finance.nacos.config.NacosConfigServiceFactory;
import com.huawei.finance.nacos.discovery.NacosAgentDirectory;
import com.huawei.finance.nacos.discovery.NacosEndpointResolver;
import com.huawei.finance.nacos.discovery.NacosRegistration;
import com.huawei.finance.nacos.discovery.NamingGateway;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Nacos 接入装配。
 *
 * <p>两半是分开开关的：
 *
 * <ul>
 *   <li><b>配置中心</b>由 {@code spring.config.import} 里有没有 {@code nacos:} 决定，
 *       在容器起来之前就发生了，与下面这个开关无关；这里只负责给它挂上热更新监听。
 *   <li><b>注册与发现</b>由 {@code huawei.finance.agent.nacos.enabled} 控制，默认关。
 * </ul>
 *
 * <p>本自动配置先于可选的 OpenJiuwen Sample 装配：后者只消费公开的 A2A 解析契约，
 * 基础设施层不再反向依赖 Sample。
 * {@link AgentEndpointResolver}，晚一步就已经用静态路由表建好了。
 */
@AutoConfiguration(beforeName = "com.huawei.finance.sample.oj.OjAgentConfiguration")
@EnableConfigurationProperties(NacosProperties.class)
public class NacosAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NacosAutoConfiguration.class);

    /**
     * 给启动时导入过的 dataId 挂热更新监听。
     *
     * <p>用 {@link ObjectProvider} 而不是 {@code @ConditionalOnBean}：那个客户端是启动的
     * ConfigData 阶段手工注册进容器的单例，条件注解对手工单例的匹配时机依赖 Boot 内部顺序，
     * 而这里不值得赌——取不到就是没用配置中心，安静跳过即可。
     */
    @Bean
    public SmartInitializingSingleton agentNacosConfigRefresh(
            ObjectProvider<NacosConfigServiceFactory> factories,
            ConfigurableEnvironment environment,
            ApplicationContext applicationContext,
            NacosProperties properties) {
        return () -> factories.ifAvailable(factory -> {
            try {
                new NacosConfigRefresher(environment, applicationContext, factory, properties).start();
            } catch (NacosException e) {
                // 监听挂不上不影响已经读到的配置，服务照常运行；但热更新从此是哑的，
                // 而「以为能热更新其实不能」比「知道不能」危险得多，所以是 ERROR
                log.error("Nacos 配置变更监听注册失败，热更新不会生效：{}", e.getMessage());
            }
        });
    }

    @Bean(destroyMethod = "shutDown")
    @ConditionalOnProperty(name = "huawei.finance.agent.nacos.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public NamingService agentNamingService(NacosProperties properties) throws NacosException {
        return NacosFactory.createNamingService(properties.toClientProperties());
    }

    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.nacos.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public NacosAgentDirectory agentDirectory(NamingService naming, NacosProperties properties) {
        var discovery = properties.getDiscovery();
        return new NacosAgentDirectory(
                NamingGateway.of(naming, discovery.getGroup()),
                discovery.getCapabilityMetadataKey(),
                discovery.getCacheMs());
    }

    /**
     * 接管领域 Agent 的地址解析。
     *
     * <p>静态路由表降级为兜底而不是被丢掉，理由见 {@link NacosEndpointResolver}。
     */
    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.nacos.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public AgentEndpointResolver agentEndpointResolver(NacosAgentDirectory directory) {
        return new NacosEndpointResolver(directory, AgentEndpointResolver.ofStatic(java.util.Map.of()));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "huawei.finance.agent.nacos.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public NacosRegistration agentNacosRegistration(
            NamingService naming, NacosProperties properties, ObjectProvider<DomainAgent> agents,
            ConfigurableEnvironment environment) {
        String agentId = environment.getProperty("huawei.finance.agent.id",
                properties.getDiscovery().getServiceName());
        String mode = environment.getProperty("huawei.finance.agent.implementation-mode", "application");
        return new NacosRegistration(naming, properties,
                advertisedCapabilities(properties, agents, agentId), agentId, mode);
    }

    /**
     * 等应用真正就绪再注册。
     *
     * <p>注册早了会在「端口已监听但依赖还没热好」的窗口里被派到单。这个窗口在本地看不出来，
     * 在行内会因为连接池预热与 JIT 而实打实存在。
     */
    @Bean
    @ConditionalOnProperty(name = "huawei.finance.agent.nacos.enabled", havingValue = "true")
    public ApplicationListener<ApplicationReadyEvent> agentNacosRegistrar(
            NacosRegistration registration, NacosProperties properties, ConfigurableEnvironment environment) {
        return event -> {
            var discovery = properties.getDiscovery();
            if (!discovery.isRegister()) {
                log.info("huawei.finance.agent.nacos.discovery.register=false，本进程只发现不注册");
                return;
            }
            registration.register(resolveIp(discovery.getIp()), resolvePort(discovery.getPort(), environment));
        };
    }

    private static Set<String> advertisedCapabilities(
            NacosProperties properties, ObjectProvider<DomainAgent> agents, String agentId) {
        Set<String> advertised = new TreeSet<>(properties.getDiscovery().getCapabilities());
        if (agentId != null && !agentId.isBlank()) {
            advertised.add(agentId);
        }
        agents.forEach(agent -> advertised.addAll(agent.advertisedCapabilities()));
        return advertised;
    }

    private static String resolveIp(String configured) {
        if (!configured.isBlank()) {
            return configured;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            // 注册一个 127.0.0.1 比不注册更坏：别的进程会拿它去连自己
            throw new IllegalStateException(
                    "取不到本机地址，请显式配置 huawei.finance.agent.nacos.discovery.ip", e);
        }
    }

    private static int resolvePort(int configured, ConfigurableEnvironment environment) {
        if (configured > 0) {
            return configured;
        }
        // local.server.port 是实际监听端口，server.port=0（随机端口）时两者不同，
        // 注册后者会得到一个 0
        Integer actual = environment.getProperty("local.server.port", Integer.class);
        if (actual != null && actual > 0) {
            return actual;
        }
        Integer configuredPort = environment.getProperty("server.port", Integer.class);
        if (configuredPort != null && configuredPort > 0) {
            return configuredPort;
        }
        throw new IllegalStateException(
                "取不到本进程的监听端口，请显式配置 huawei.finance.agent.nacos.discovery.port");
    }
}
