package com.huawei.finance.gateway;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;

/**
 * 网关往返记账装饰器：给任何 {@link ModelGatewayClient} 实现套上按用途计数。
 *
 * <p>为什么是装饰器而不是写在实现类里。原先计数长在 {@code OpenAiCompatibleModelGateway}
 * 内部，有两个后果：换一个实现类（行内统一推理入口就是要换的）记账就静悄悄失效了；
 * 测试里的假网关不计数，看板与用例都看不到真实往返序列。
 *
 * <p>计的是**调用次数**，不是成功发出的 HTTP 次数。原先在实现类里计数的位置在密钥检查
 * 之后——没有密钥就直接降级返回，一次也不记。而 CI 环境恰恰没有密钥，意味着「谁多打了一次」
 * 在回归里看不见。按调用次数计，无论网关是否可用，多打一次就一定被记上。
 *
 * <p>入参为空的调用不计：那是调用方自己就该短路掉的空操作，不构成对模型的往返。
 *
 * <p><b>不再设次数硬上限</b>（ADR-003）。次数只进 {@link RequestContext#gatewayCalls()}，
 * 请求结束时由快路径记入 {@code huawei.finance.agent.gateway.round_trips} summary；开重排等多一次调用
 * 不再打「超预算」。Agent 工具轮次登记为 {@code agent-tools}，与面客链路用途分开看。
 *
 * <p>{@link MeterRegistry} 仍保留在构造上，与装配一致；次数 summary 在请求收口处记，
 * 这里只负责把用途写进上下文。
 */
public class BudgetAwareModelGateway implements ModelGatewayClient {

    static final String PURPOSE_EMBEDDING = "embedding";
    static final String PURPOSE_ARBITRATION = "arbitration";
    static final String PURPOSE_RERANK = "rerank";
    static final String PURPOSE_AGENT_TOOLS = "agent-tools";

    private final ModelGatewayClient delegate;

    public BudgetAwareModelGateway(ModelGatewayClient delegate, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        // meterRegistry 故意未用：装配与历史构造签名保留，指标在请求收口处记 summary
    }

    @Override
    public GatewayResult<List<float[]>> embed(List<String> inputs) {
        if (inputs != null && !inputs.isEmpty()) {
            record(PURPOSE_EMBEDDING);
        }
        return delegate.embed(inputs);
    }

    @Override
    public GatewayResult<String> chat(ChatRequest request) {
        record(PURPOSE_ARBITRATION);
        return delegate.chat(request);
    }

    /**
     * Agent 的工具调用轮次如实登记为 {@code agent-tools}，与面客链路的 embedding/arbitration
     * 分开看。慢路径本来就是多轮，和快路径的往返序列不宜混成一条曲线。
     */
    @Override
    public GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
        record(PURPOSE_AGENT_TOOLS);
        return delegate.chatWithTools(request);
    }

    @Override
    public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
        if (documents != null && !documents.isEmpty()) {
            record(PURPOSE_RERANK);
        }
        return delegate.rerank(query, documents, topN);
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    private void record(String purpose) {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx != null) {
            ctx.recordGatewayRoundTrip(purpose);
        }
    }
}
