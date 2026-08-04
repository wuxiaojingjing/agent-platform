package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.product.mobilebanking.api.ChatRequestDto;
import com.huawei.finance.product.mobilebanking.api.ChatResponseDto;
import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.registry.index.IndexReadiness;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 非降级态验收：该用的都真的用上了。
 *
 * <p>其余用例验的是「降级时也给得出合理出口」，这是设计内的分支，所以它们在模型不可达时
 * 照样全绿。问题是**这条绿灯会一直亮着**：模型网关曾因为消息转换器配错而每次调用都失败，
 * 整套测试跟着跑在降级态长达数周，没有一条变红。等转换器修好、模型真正接通，
 * 四条端到端用例立刻暴露出一个只在非降级态才出现的缺口（模型判出多意图而召回没判，
 * 于是回复层说不出是哪几件事）。
 *
 * <p>所以要有这么一条反向的用例：**在本该完整可用的环境里，任何降级都算失败。**
 * 它不检查出口对不对——那是别的用例的事；它只检查这次判定是不是真的用上了语义召回与模型。
 *
 * <p>开关是独立的 {@code HUAWEI_FINANCE_AGENT_REQUIRE_LIVE_MODEL}，而不是「有 API key 就开」。
 * 本地优先读取环境变量，CI 也可传递同名 JVM 属性，避免 Surefire 的环境过滤造成假绿。
 * 密钥在开发机上长期存在，但那里的网络随时可能被代理劫持——按密钥开，
 * 第一次因为 VPN 抖动红掉之后，这条用例就会被人加上 assumeTrue 绕过去，
 * 而它恰恰是不能被绕过的那条。显式声明「我这个环境应当完整可用」才是能守住的边界。
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoDegradationTest {

    private static final String PG_URL = "jdbc:postgresql://localhost:5432/agent_platform";

    static boolean liveModelRequired() {
        String environment = System.getenv("HUAWEI_FINANCE_AGENT_REQUIRE_LIVE_MODEL");
        String configured = environment != null && !environment.isBlank()
                ? environment
                : System.getProperty("HUAWEI_FINANCE_AGENT_REQUIRE_LIVE_MODEL", "false");
        return Boolean.parseBoolean(configured);
    }

    /** 覆盖三条不同链路：命中强规则、要经模型仲裁、多意图。降级会在其中任意一条上现形。 */
    private static final List<String> PROBES = List.of(
            "查一下我的余额",
            "帮我转两千给张三",
            "查余额，再给老徐转 1000；不足就别转");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RedissonClient redisson;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private IndexReadiness readiness;

    @BeforeAll
    static void requireMiddleware() {
        assumeTrue(liveModelRequired(), "未声明本环境应当完整可用，跳过非降级态验收");
        assumeTrue(redisAnswersPing(), "Redis 未就绪，跳过非降级态验收");
        assumeTrue(postgresAccepts(), "Postgres 未就绪，跳过非降级态验收");
    }

    @BeforeEach
    void clearDecisionCache() {
        // 缓存命中会让请求在一级短路就返回，既不碰语义召回也不碰模型——
        // 那样这条用例会在一个什么都没验到的情况下变绿
        redisson.getKeys().deleteByPattern("huawei-finance-agent:route-decision:v3:*");
        redisson.getKeys().deleteByPattern("huawei-finance-agent:decision-meta:*");
    }

    @Test
    @DisplayName("索引必须带向量：没有向量就没有语义通道，混合召回退化成纯词面")
    void semanticChannelIsReallyOn() {
        IndexReadiness.Snapshot index = readiness.get();

        assertThat(index.searchable()).as("索引未就绪，检索通道整体停用").isTrue();
        assertThat(index.semanticAvailable())
                .as("索引里没有向量。多半是建索引时 embedding 调用失败了，"
                        + "而那个失败只在启动日志里留了一行 WARN")
                .isTrue();
    }

    @Test
    @DisplayName("完整环境下一次降级都不许有")
    void nothingDegrades() {
        double before = degradedTotal();

        List<ChatResponseDto> responses = new ArrayList<>();
        for (String probe : PROBES) {
            responses.add(chat(probe));
        }

        for (int i = 0; i < PROBES.size(); i++) {
            assertThat(responses.get(i).degradedChannels())
                    .as("「%s」有召回通道被摘除", PROBES.get(i))
                    .isEmpty();
        }

        // 用增量而不是总量：启动期建索引若失败过，总量从一开始就不是零，
        // 那时断言总量为零得到的是一条与本次请求无关的红
        assertThat(degradedTotal() - before)
                .as("本次探测触发了降级。降级本身是设计内的分支，但在声明为完整可用的环境里，"
                        + "它意味着有个东西没连上，而系统正安静地用次优路径服务")
                .isZero();

        // 不要求每条都经模型：强规则命中与三级短路本就不该调模型，那是省下来的往返。
        // 但三条探测里一条都没到过模型，说明这个环境根本没在用模型，
        // 前两条用例的绿就没有意义
        List<String> versions = responses.stream()
                .map(ChatResponseDto::decision)
                .map(RouteDecision::modelVersion)
                .filter(version -> !RouteDecision.VERSION_NONE.equals(version))
                .toList();
        assertThat(versions)
                .as("三条探测无一经过模型仲裁，本环境的「零降级」是空的")
                .isNotEmpty();
    }

    private double degradedTotal() {
        return meters.find(AgentMetrics.DEGRADED).counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private ChatResponseDto chat(String query) {
        ChatRequestDto request = new ChatRequestDto(
                "live-" + UUID.randomUUID(), "u-live", query, "WEB", "home", "LOGGED_IN");
        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-User-ID", "u-live");
        headers.add("X-Space-ID", "default");
        headers.add("X-Channel-ID", "WEB");
        return rest.postForObject("http://localhost:" + port + "/api/v1/chat",
                new org.springframework.http.HttpEntity<>(request, headers), ChatResponseDto.class);
    }

    private static boolean redisAnswersPing() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 6379), 1000);
            socket.setSoTimeout(1000);
            socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[8];
            int read = socket.getInputStream().read(buffer);
            return read > 0 && new String(buffer, 0, read, StandardCharsets.US_ASCII).startsWith("+PONG");
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean postgresAccepts() {
        try (Connection ignored = DriverManager.getConnection(PG_URL, "agent_platform", "agent_platform")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
}
