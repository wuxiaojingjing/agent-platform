package com.huawei.finance.nacos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.awaitility.Awaitility.await;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.huawei.finance.nacos.config.NacosConfigServiceFactory;
import com.huawei.finance.nacos.discovery.NacosAgentDirectory;
import com.huawei.finance.nacos.discovery.NacosRegistration;
import com.huawei.finance.nacos.discovery.NamingGateway;
import java.net.Socket;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对着真 Nacos 跑。没起 Nacos 时整类跳过——**跳过不等于通过**，发布门禁要单独确认这批跑过。
 *
 * <p>用替身证不了这里的两件事：客户端与服务端的协议版本对不对得上（2.x 起走 gRPC，
 * 只开 8848 不开 9848 时表现为控制台能开、客户端连不上），以及注册后多久能被发现。
 */
class NacosLiveTest {

    private static final String SERVER = "127.0.0.1:8848";
    private static final String GROUP = "HUAWEI_FINANCE_AGENT_TEST";

    private static boolean nacosUp;
    private static NamingService naming;
    private static ConfigService config;

    @BeforeAll
    static void probe() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 8848), 500);
            nacosUp = true;
        } catch (Exception e) {
            nacosUp = false;
            return;
        }
        var properties = new NacosProperties();
        properties.setServerAddr(SERVER);
        naming = NacosFactory.createNamingService(properties.toClientProperties());
        config = new NacosConfigServiceFactory(properties).get();
    }

    @AfterAll
    static void shutdown() throws Exception {
        if (naming != null) {
            naming.shutDown();
        }
        if (config != null) {
            config.shutDown();
        }
    }

    @BeforeEach
    void requireNacos() {
        assumeTrue(nacosUp, "Nacos 未就绪（127.0.0.1:8848），跳过注册中心集成测试");
    }

    @Test
    @DisplayName("注册进去能被发现，摘除之后不再被派单")
    void registerThenDeregister() {
        var properties = new NacosProperties();
        properties.setServerAddr(SERVER);
        properties.getDiscovery().setGroup(GROUP);
        properties.getDiscovery().setServiceName("agent-platform-live-test");

        var registration = new NacosRegistration(naming, properties, Set.of("cap.live.test"));
        var directory = new NacosAgentDirectory(NamingGateway.of(naming, GROUP), "huawei.finance.agent.capabilities", 0);

        registration.register("127.0.0.1", 18080);
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(directory.resolve("cap.live.test")).contains("http://127.0.0.1:18080"));
        } finally {
            registration.close();
        }

        // 显式摘除的意义就在这里：不等心跳超时那十几秒。发布期间的那十几秒里，
        // 请求会持续打在一个正在关闭的实例上
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(directory.resolve("cap.live.test")).isEmpty());
    }

    @Test
    @DisplayName("配置发布后能读回来")
    void publishThenRead() throws Exception {
        String dataId = "agent-platform-live-test.yaml";
        config.publishConfig(dataId, GROUP, "huawei:\n  finance:\n    agent:\n      demo:\n        topK: 42\n");
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(config.getConfig(dataId, GROUP, 2000)).contains("topK: 42"));
        } finally {
            config.removeConfig(dataId, GROUP);
        }
    }
}
