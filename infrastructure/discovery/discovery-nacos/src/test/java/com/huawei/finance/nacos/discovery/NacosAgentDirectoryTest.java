package com.huawei.finance.nacos.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 从注册中心的实例清单推出「谁办哪件事」。不连真 Nacos。 */
class NacosAgentDirectoryTest {

    private static final String CAPABILITY_KEY = "huawei.finance.agent.capabilities";

    @Test
    @DisplayName("能力清单来自实例元数据，不从服务名猜")
    void capabilitiesComeFromInstanceMetadata() {
        var naming = new FakeNaming();
        naming.add("agent-账户", instance("10.0.0.1", 8080, true, "cap.balance,cap.detail"));

        var directory = directory(naming);

        assertThat(directory.knownCapabilities()).containsExactly("cap.balance", "cap.detail");
        assertThat(directory.resolve("cap.balance")).contains("http://10.0.0.1:8080");
        assertThat(directory.resolve("cap.transfer")).isEmpty();
    }

    @Test
    @DisplayName("不健康的实例不派单，但仍出现在清单里")
    void unhealthyInstancesAreListedButNotDispatchedTo() {
        var naming = new FakeNaming();
        naming.add("agent-支付", instance("10.0.0.2", 8080, false, "cap.transfer"));

        var directory = directory(naming);

        assertThat(directory.resolve("cap.transfer")).isEmpty();
        assertThat(directory.instancesFor("cap.transfer"))
                .as("「有这个 Agent 但它挂了」与「压根没有这个 Agent」是排障时最需要分清的两件事")
                .singleElement()
                .satisfies(agent -> assertThat(agent.healthy()).isFalse());
    }

    @Test
    @DisplayName("多个健康实例轮流派单，而不是全压在第一个上")
    void spreadsAcrossHealthyInstances() {
        var naming = new FakeNaming();
        naming.add("agent-支付", instance("10.0.0.1", 8080, true, "cap.transfer"));
        naming.add("agent-支付", instance("10.0.0.2", 8080, true, "cap.transfer"));

        var directory = directory(naming);
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            picked.add(directory.resolve("cap.transfer").orElseThrow());
        }

        assertThat(picked).containsExactly(
                "http://10.0.0.1:8080", "http://10.0.0.2:8080",
                "http://10.0.0.1:8080", "http://10.0.0.2:8080");
    }

    /**
     * 注册中心抖一下不该变成全量业务失败。
     *
     * <p>返回空表等于把所有能力一次性判成无人承接：中控会把在途任务全判失败，
     * 而这些任务本来只是需要多等两秒。
     */
    @Test
    @DisplayName("注册中心不可用时沿用上一次的清单，不返回空表")
    void keepsLastKnownGoodWhenRegistryIsDown() {
        var naming = new FakeNaming();
        naming.add("agent-支付", instance("10.0.0.1", 8080, true, "cap.transfer"));
        var directory = directory(naming);
        assertThat(directory.resolve("cap.transfer")).isPresent();

        naming.broken = true;

        assertThat(directory.resolve("cap.transfer")).contains("http://10.0.0.1:8080");
    }

    @Test
    @DisplayName("注册中心上有实例时压过静态路由表；没有时才回落")
    void discoveryWinsOverStaticTable() {
        var naming = new FakeNaming();
        naming.add("agent-支付", instance("10.0.0.1", 8080, true, "cap.transfer"));
        var resolver = new NacosEndpointResolver(
                directory(naming),
                AgentEndpointResolver.ofStatic(Map.of(
                        "cap.transfer", "http://旧地址:8080",
                        "cap.balance", "http://还没注册的:8080")));

        assertThat(resolver.resolve("cap.transfer")).contains("http://10.0.0.1:8080");
        assertThat(resolver.resolve("cap.balance"))
                .as("迁移期一定是混合的：还没注册的领域不能因为接了注册中心就停摆")
                .contains("http://还没注册的:8080");
        assertThat(resolver.knownCapabilities()).containsExactly("cap.balance", "cap.transfer");
    }

    private static NacosAgentDirectory directory(FakeNaming naming) {
        // 缓存关掉，用例要看的是每次判定的结果而不是缓存行为
        return new NacosAgentDirectory(naming, CAPABILITY_KEY, 0);
    }

    private static Instance instance(String ip, int port, boolean healthy, String capabilities) {
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setHealthy(healthy);
        instance.setEnabled(true);
        instance.setMetadata(new LinkedHashMap<>(Map.of(CAPABILITY_KEY, capabilities)));
        return instance;
    }

    static class FakeNaming implements NamingGateway {

        private final Map<String, List<Instance>> byService = new LinkedHashMap<>();

        boolean broken = false;

        void add(String service, Instance instance) {
            byService.computeIfAbsent(service, key -> new ArrayList<>()).add(instance);
        }

        @Override
        public List<String> services() throws NacosException {
            if (broken) {
                throw new NacosException(500, "注册中心不可用");
            }
            return List.copyOf(byService.keySet());
        }

        @Override
        public List<Instance> instances(String serviceName) throws NacosException {
            if (broken) {
                throw new NacosException(500, "注册中心不可用");
            }
            return byService.getOrDefault(serviceName, List.of());
        }
    }
}
