package com.huawei.finance.sample.oj.e2e;

import com.huawei.finance.sample.oj.OjDomainAgent;
import com.huawei.finance.sample.oj.OjQueryCodec;
import com.huawei.finance.sample.oj.StubTransferAgent;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.tck.DomainAgentContract;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

/**
 * 拿 TCK 验这条 OJ 链路：把 {@link OjDomainAgent} 当作被测实现，隔着完整 HTTP 契约跑用例。
 *
 * <p>这一步不是重复劳动。TCK 原本是给行内验**自己的实现**用的，而这里验的是
 * **中间这条链路有没有破坏契约**——两者会坏在完全不同的地方：
 *
 * <ul>
 *   <li>服务端那个 {@code StubTransferAgent} 是合规的，所以这里跑红只可能是链路的错：
 *       比如 {@code taskId} 没回显、幂等键在序列化中丢了、失败被翻成了成功。</li>
 *   <li>反过来，链路正确但行内实现不合规，是行内自己继承 TCK 时该发现的事。</li>
 * </ul>
 *
 * <p>换句话说：契约不只要在进程内成立，隔着一次网络往返也得成立。
 * 而「幂等键在 JSON 往返里丢了」这类问题，只有这样跑才看得见。
 */
@SpringBootTest(
        classes = {OjTestServerApplication.class, OjDomainAgentContractTest.ServerAgents.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {"huawei.finance.sample.openjiuwen.server.enabled=true", "huawei.finance.sample.openjiuwen.enabled=false"})
class OjDomainAgentContractTest extends DomainAgentContract {

    @Autowired
    WebApplicationContext webApplicationContext;

    @TestConfiguration
    static class ServerAgents {
        @Bean
        DomainAgent stubTransferAgent() {
            return new StubTransferAgent();
        }
    }

    @Override
    protected DomainAgent agent() {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        RestClient restClient = RestClient.builder()
                .requestFactory(new MockMvcClientHttpRequestFactory(mockMvc))
                .build();
        return new OjDomainAgent(restClient, new OjQueryCodec(),
                Map.of(StubTransferAgent.CAPABILITY, "http://openjiuwen-agent"));
    }

    @Override
    protected String capabilityId() {
        return StubTransferAgent.CAPABILITY;
    }

    @Override
    protected Map<String, Object> validParameters() {
        return Map.of("payee", "张三", "amount", "1000");
    }
}
