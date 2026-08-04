package com.huawei.finance.product.mobilebanking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.nacos.discovery.NacosAgentDirectory;
import com.huawei.finance.nacos.discovery.NamingGateway;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 「有哪些智能体」这个问题的答案。
 *
 * <p>不启动上下文：要验的是两类来源怎么合并呈现，与 Web 层无关。
 */
class AgentDirectoryControllerTest {

    @Test
    @DisplayName("没接注册中心时如实说 enabled=false，而不是假装列表为空")
    void tellsTheTruthWhenRegistryIsAbsent() {
        var controller = new AgentDirectoryController(List.of(new FakeAgent()), absent(), absent());

        Map<String, Object> body = controller.agents();

        assertThat(body.get("registry")).isEqualTo(Map.of("enabled", false));
        assertThat(asList(body.get("inProcess")))
                .singleElement()
                .isEqualTo(Map.of("agent", "FakeAgent",
                        "capabilities", new java.util.TreeSet<>(Set.of("cap.demo"))));
    }

    /**
     * 进程内与注册中心分开列。
     *
     * <p>合成一张表会丢掉最关键的一条信息：进程内那些装上了就一定在，
     * 注册中心上那些随时可能下线。
     */
    @Test
    @DisplayName("注册中心上的实例带健康状态一起列出")
    void listsRegistryInstancesWithHealth() {
        var naming = new StubNaming();
        var controller = new AgentDirectoryController(
                List.of(new FakeAgent()),
                present(new NacosAgentDirectory(naming, "huawei.finance.agent.capabilities", 0)),
                absent());

        Map<String, Object> registry = asMap(controller.agents().get("registry"));

        assertThat(registry.get("enabled")).isEqualTo(true);
        assertThat(asList(registry.get("instances"))).hasSize(1).first()
                .satisfies(instance -> {
                    Map<String, Object> described = asMap(instance);
                    assertThat(described.get("baseUrl")).isEqualTo("http://10.0.0.9:8080");
                    assertThat(described.get("healthy")).isEqualTo(false);
                    assertThat(described.get("capabilities")).isEqualTo(
                            new java.util.TreeSet<>(Set.of("cap.transfer")));
                });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static <T> ObjectProvider<T> absent() {
        return new SimpleProvider(null);
    }

    private static <T> ObjectProvider<T> present(T directory) {
        return new SimpleProvider(directory);
    }

    /** 只实现取值那一个方法，其余交给接口默认实现。 */
    record SimpleProvider<T>(T value) implements ObjectProvider<T> {

        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }
    }

    static class FakeAgent implements DomainAgent {

        @Override
        public boolean supports(String capabilityId) {
            return "cap.demo".equals(capabilityId);
        }

        @Override
        public Set<String> advertisedCapabilities() {
            return Set.of("cap.demo");
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            throw new UnsupportedOperationException("清单用例不执行任务");
        }
    }

    static class StubNaming implements NamingGateway {

        @Override
        public List<String> services() {
            return List.of("agent-支付");
        }

        @Override
        public List<Instance> instances(String serviceName) {
            Instance instance = new Instance();
            instance.setIp("10.0.0.9");
            instance.setPort(8080);
            instance.setHealthy(false);
            instance.setEnabled(true);
            instance.setMetadata(new java.util.LinkedHashMap<>(
                    Map.of("huawei.finance.agent.capabilities", "cap.transfer")));
            return List.of(instance);
        }
    }
}
