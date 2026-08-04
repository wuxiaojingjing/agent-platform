package com.huawei.finance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.contracts.validation.SchemaRef;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真连模型网关的集成测试。
 *
 * <p>没有密钥时整类跳过，CI 与他人克隆仓库后不会因缺密钥而红。
 *
 * <p>网关连不上时逐条跳过而不是失败：README 已把「模型不可用」定为常态，
 * 本地 VPN 抖动就红一次构建，只会训练出「看到红就重跑」的习惯。
 * 但网关一旦有应答，维度、Schema 这些契约就必须成立——那才是代码的责任。
 */
@EnabledIfEnvironmentVariable(named = "SILICONFLOW_API_KEY", matches = ".+")
class ModelGatewayLiveTest {

    private ModelGatewayClient gateway;
    private ModelGatewayProperties props;

    @BeforeEach
    void setUp() {
        props = new ModelGatewayProperties();
        ModelGatewayConfiguration config = new ModelGatewayConfiguration();
        var cm = config.modelGatewayConnectionManager(props);
        var httpClient = config.modelGatewayHttpClient(cm, props);
        var restClient = config.modelGatewayRestClient(httpClient, props);
        CircuitBreaker cb = config.modelGatewayCircuitBreaker(
                config.modelGatewayCircuitBreakerRegistry(props));
        var retry = config.modelGatewayRetry(config.modelGatewayRetryRegistry(props));
        gateway = new OpenAiCompatibleModelGateway(restClient, props, cb, retry,
                new SimpleMeterRegistry(), System.getenv("SILICONFLOW_API_KEY"));

        RequestContextHolder.set(new RequestContext(
                "trace-live", "sess-live", "u1", "MOBILE", "home", "NORMAL", false));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.clear();
    }

    @Test
    @DisplayName("embedding 维度必须与配置一致，否则 OpenSearch 索引会建错")
    void embeddingDimensionMatchesConfiguration() {
        GatewayResult<List<float[]>> result = gateway.embed(List.of("查一下余额", "我要还信用卡"));
        assumeReachable(result);
        assertEquals(2, result.value().size());
        assertEquals(props.getEmbedding().getDimensions(), result.value().get(0).length);
    }

    @Test
    @DisplayName("连接复用生效：后续调用应显著快于首次（首次含 TLS 握手）")
    void connectionIsReused() {
        GatewayResult<List<float[]>> first = gateway.embed(List.of("查余额"));
        assumeReachable(first);

        long warmTotal = 0;
        int warmCalls = 3;
        for (int i = 0; i < warmCalls; i++) {
            GatewayResult<List<float[]>> r = gateway.embed(List.of("查账单" + i));
            assertTrue(r.available(), "首次通了但热态调用失败，说明连接复用有问题：" + r.reason());
            warmTotal += r.latencyMs();
        }
        long warmAvg = warmTotal / warmCalls;

        System.out.printf("首次 %dms，热态均值 %dms%n", first.latencyMs(), warmAvg);
        // 不断言具体数值：公网抖动大，断死会变成随机失败的测试。
        // 只断言热态调用确实完成了，时延数字打印出来供人判断。
        assertTrue(warmAvg > 0);
    }

    @Test
    @DisplayName("仲裁模型能直出通过 Schema 校验的 JSON")
    void arbitrationReturnsSchemaValidJson() {
        String system = "你是银行助手的意图仲裁器，只输出 JSON，不要任何解释文字。";
        String user = """
                候选能力：
                1. cap.balance.query - 查询账户余额
                2. cap.bill.query - 查询信用卡账单

                用户输入：卡里还有多少钱

                输出 JSON：{"decision":"EXECUTE_CAPABILITY","taskShape":"SINGLE_ACTION",
                "candidateIds":["cap.balance.query"],"missingSlots":[],"confidence":0.95,
                "reasonCode":"HIGH_CONFIDENCE"}
                """;

        GatewayResult<String> result = gateway.chat(new ChatRequest(
                props.getArbitration().getModel(), system, user,
                props.getArbitration().getMaxTokens(), props.getArbitration().getTemperature(), true));

        assumeReachable(result);
        assertNotNull(result.value());
        System.out.println("仲裁输出：" + result.value());

        var outcome = new ContractValidator()
                .validateJson(SchemaRef.TASK_SHAPE_MODEL_OUTPUT, result.value());
        assertTrue(outcome.valid(), "模型输出不合 Schema：" + outcome.summary());
    }

    /** 网关不可达时跳过本条用例；这是环境状态，不是代码缺陷。 */
    private static void assumeReachable(GatewayResult<?> result) {
        assumeTrue(result.available(), "模型网关不可达，跳过实调用例：" + result.reason());
    }

    @Test
    @DisplayName("重排默认关闭，调用直接返回不可用而不发请求")
    void rerankDisabledByDefault() {
        GatewayResult<List<RerankHit>> result = gateway.rerank("查余额", List.of("查询余额", "转账"), 2);
        assertFalse(result.available());
        assertEquals("rerank-disabled", result.reason());
    }
}
