package com.huawei.finance.nacos;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos 接入配置。
 *
 * <p>这个类会被读两次：一次在 {@code ConfigData} 阶段由 {@code Binder} 直接绑定（那时容器还没有，
 * 拿不到 Bean），一次作为普通的 {@code @ConfigurationProperties}。所以它必须是无参可构造、
 * setter 可写的普通类，不能是 record。
 *
 * <p><b>连接参数自己不能放在 Nacos 上</b>——这是显然的循环依赖，但真会有人试（把 serverAddr
 * 写进 Nacos 的公共配置里）。因此这一组键不在热更新白名单内，改了也要重启。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.nacos")
public class NacosProperties {

    /**
     * 服务注册与发现开关。默认关。
     *
     * <p>与配置中心无关：配置中心是否启用由 {@code spring.config.import} 里有没有
     * {@code nacos:} 决定。两者分开控制是有意的——很多行内环境只给配置中心不给注册中心，
     * 合成一个开关会逼人为了用配置中心而把注册也打开。
     */
    private boolean enabled = false;

    /** 形如 {@code host:8848}，多个用逗号分隔。 */
    private String serverAddr = "127.0.0.1:8848";

    private String namespace = "";

    private String username = "";

    private String password = "";

    private final Config config = new Config();

    private final Discovery discovery = new Discovery();

    /** 组装 nacos-client 要的 {@link Properties}。连接参数只有这一处来源。 */
    public Properties toClientProperties() {
        Properties props = new Properties();
        props.put("serverAddr", serverAddr);
        if (!namespace.isBlank()) {
            props.put("namespace", namespace);
        }
        if (!username.isBlank()) {
            props.put("username", username);
            props.put("password", password);
        }
        return props;
    }

    public static class Config {

        private String group = "DEFAULT_GROUP";

        /**
         * 拉配置的超时。
         *
         * <p>启动期同步阻塞，所以不能配大：配置中心不可用时，这个值就是每个 dataId
         * 往启动时间上加的秒数。宁可启动时读不到（用本地默认值起来）也不要起不来——
         * 配置中心挂了不该让面客服务跟着起不来。
         */
        private int timeoutMs = 3000;

        /**
         * 允许热更新的键前缀白名单。
         *
         * <p>空表示**任何键都不热更新**，推下来只记日志。这是默认值，也是安全的一侧：
         * 一个键能不能热更新，取决于消费它的代码是每次都读、还是启动时抄走了一份，
         * 而后者占多数。不在白名单里的键推下来只会打 WARN，不会静默生效，
         * 也不会静默不生效——「以为改了其实没改」是这类系统最常见的事故。
         */
        private List<String> refreshable = new ArrayList<>();

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public List<String> getRefreshable() {
            return refreshable;
        }

        public void setRefreshable(List<String> refreshable) {
            this.refreshable = refreshable;
        }
    }

    public static class Discovery {

        /** 领域 Agent 注册在哪个组。与业务配置分组隔开，避免两类东西混在一个命名空间里。 */
        private String group = "HUAWEI_FINANCE_AGENT_AGENT";

        /** 本进程注册用的服务名。 */
        private String serviceName = "mobile-banking-assistant";

        /** 是否把本进程注册上去。只做消费者（只发现不注册）时关掉。 */
        private boolean register = true;

        /**
         * 实例元数据里放能力清单的键。
         *
         * <p>能力清单跟着实例走，而不是靠服务名猜：服务名与能力是多对多的
         * （一个领域进程承接多个能力），用命名约定去反推迟早会错。
         */
        private String capabilityMetadataKey = "huawei.finance.agent.capabilities";

        /**
         * 发现结果的缓存时长。
         *
         * <p>「列服务」是一次远端调用，不缓存就等于每次派单多一个网络往返。
         * 这个值同时也是「新领域进程上线后最迟多久会被派到单」的上界。
         */
        private int cacheMs = 5000;

        /** 注册用的 IP。留空则取本机地址；容器与多网卡环境下必须显式配。 */
        private String ip = "";

        /** 注册用的端口。留空（0）则取实际监听端口。 */
        private int port = 0;

        /**
         * 本进程声称承接的能力。
         *
         * <p>留空则取进程内各领域 Agent 自报的清单（{@code DomainAgent.advertisedCapabilities}）。
         * 不从能力卡资产里推：资产里有的是「系统支持哪些能力」，不是「这个进程办哪些」。
         */
        private java.util.List<String> capabilities = new ArrayList<>();

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public java.util.List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(java.util.List<String> capabilities) {
            this.capabilities = capabilities;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public boolean isRegister() {
            return register;
        }

        public void setRegister(boolean register) {
            this.register = register;
        }

        public String getCapabilityMetadataKey() {
            return capabilityMetadataKey;
        }

        public void setCapabilityMetadataKey(String capabilityMetadataKey) {
            this.capabilityMetadataKey = capabilityMetadataKey;
        }

        public int getCacheMs() {
            return cacheMs;
        }

        public void setCacheMs(int cacheMs) {
            this.cacheMs = cacheMs;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Config getConfig() {
        return config;
    }

    public Discovery getDiscovery() {
        return discovery;
    }
}
