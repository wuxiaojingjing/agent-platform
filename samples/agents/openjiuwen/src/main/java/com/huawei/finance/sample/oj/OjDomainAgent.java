package com.huawei.finance.sample.oj;

import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.QueryResponse;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 中控侧的 {@link DomainAgent}：把任务经 {@code POST /v1/query} 发给远端 OpenJiuwen Agent Server。
 *
 * <p>它是一个**适配器**，本身不含业务：中控看到的仍然只有 {@link DomainAgent} 这一个口子，
 * 换成进程内实现、换成别的 RPC，都不影响上游。
 *
 * <h2>失败一律成为结果，不成为异常</h2>
 *
 * <p>这里把连接失败、超时、非 2xx、信封不符统统翻成 {@code FAILED} 的 {@link TaskResult}，
 * 而不是往上抛。理由是中控的补偿与回复生成都以 {@link TaskResult} 为输入；抛异常会让
 * 「这次失败能不能重试」这条信息只剩一个堆栈，而那正是中控要做判断的依据。
 *
 * <p>分类上取一条保守的线：**凡是无法确认领域侧到底执行了没有的，一律
 * {@link Enums.FailureClass#RETRYABLE}**（连不上、超时、5xx）。这不是说重试一定安全——
 * 而是安全性由幂等键保证：同一个幂等键重放在领域侧必须收敛（{@code agent-tck} 的
 * {@code DomainAgentContract} 就在验这一条）。反过来，若把超时判成 FATAL，
 * 一笔实际已扣款的转账会被中控当成没发生，那是更坏的一侧。
 */
public class OjDomainAgent implements DomainAgent {

    private static final Logger log = LoggerFactory.getLogger(OjDomainAgent.class);

    private static final String QUERY_PATH = "/v1/query";

    private final RestClient restClient;
    private final OjQueryCodec codec;
    private final AgentEndpointResolver endpoints;

    public OjDomainAgent(RestClient restClient, OjQueryCodec codec, Map<String, String> endpoints) {
        this(restClient, codec, AgentEndpointResolver.ofStatic(endpoints));
    }

    public OjDomainAgent(RestClient restClient, OjQueryCodec codec, AgentEndpointResolver endpoints) {
        this.restClient = restClient;
        this.codec = codec;
        this.endpoints = endpoints;
    }

    /**
     * 只承接解析得出地址的能力。
     *
     * <p>解析不出就返回 false，让中控走「无人承接」那条路，而不是发给一个猜出来的地址。
     * 接了注册中心之后这条判定会随实例上下线变化：一个领域进程全下线，它承接的能力
     * 当场变成无人承接，而不是继续派单到一个连不上的地址上等超时。
     */
    @Override
    public boolean supports(String capabilityId) {
        return capabilityId != null && endpoints.resolve(capabilityId).isPresent();
    }

    /** 已知能承接的能力集合，供装配期做启动自检与日志。 */
    public Set<String> supportedCapabilities() {
        return endpoints.knownCapabilities();
    }

    @Override
    public Set<String> advertisedCapabilities() {
        return endpoints.knownCapabilities();
    }

    /** 地址来源，只用于日志与清单展示。 */
    public String endpointSource() {
        return endpoints.source();
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        String baseUrl = endpoints.resolve(task.capabilityId()).orElse(null);
        if (baseUrl == null) {
            // supports() 已经挡过一次，走到这里说明中控派错了。算 FATAL：重试也不会有地址
            log.error("能力 {} 没有配 Agent Server 地址", task.capabilityId());
            return OjQueryCodec.failure(task, Enums.FailureClass.FATAL, "NO_ENDPOINT");
        }

        QueryRequest request = codec.encode(task);
        QueryResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + QUERY_PATH)
                    .body(request)
                    .retrieve()
                    .body(QueryResponse.class);
        } catch (RestClientException e) {
            // 含连接失败、超时、非 2xx。一律 RETRYABLE：这些情形下无法断定领域侧是否已执行，
            // 判成 FATAL 会让一笔可能已生效的操作被当作没发生
            log.warn("调用 {} 执行 {} 失败：{}", baseUrl, task.capabilityId(), e.getMessage());
            return OjQueryCodec.failure(task, Enums.FailureClass.RETRYABLE, "OJ_TRANSPORT_ERROR");
        }

        try {
            TaskResult result = codec.decodeResult(response);
            if (!task.taskId().equals(result.taskId())) {
                // 回来的结果对应的不是这一单。可能是缓存串了、也可能是网关把响应发错了。
                // 这种情形下这个结果本身可能是真的，只是不属于当前这一单，
                // 不能采信也不能忽略，只能整单失败并留日志
                log.error("OJ 返回的 taskId {} 与请求的 {} 不一致", result.taskId(), task.taskId());
                return OjQueryCodec.failure(task, Enums.FailureClass.FATAL, "OJ_TASK_ID_MISMATCH");
            }
            return result;
        } catch (OjCodecException e) {
            // 响应到了但不认。最典型的是请求打到了通用对话 Handler 上，
            // 拿回一段「已为您办好」的自然语言。判 FATAL 而非 RETRYABLE：
            // 这是配置或部署错了，重试只会再拿一段编出来的话
            log.error("OJ 响应不符合信封契约，按失败处理：{}", e.getMessage());
            return OjQueryCodec.failure(task, Enums.FailureClass.FATAL, "OJ_CONTRACT_VIOLATION");
        }
    }
}
