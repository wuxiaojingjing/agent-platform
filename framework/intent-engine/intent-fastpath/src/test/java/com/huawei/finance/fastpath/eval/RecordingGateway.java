package com.huawei.finance.fastpath.eval;

import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import java.util.List;
import java.util.Optional;

/**
 * 透明代理，顺路把仲裁那一次的 prompt 原样录下来。
 *
 * <p>只录不改：请求照原样转发，返回照原样交回。之所以要走「录」而不是在工具里按模板重拼，
 * 是因为重拼出来的 prompt 与线上真实 prompt 之间会留一条**会慢慢分叉的缝**——
 * 占位符换了顺序、候选行多了一个字段，重拼版都还能跑，只是它优化的已经不是线上那份了。
 * 这种失败不报错，只是效果不如预期，事后极难归因。
 *
 * <p>识别仲裁调用靠 {@code jsonMode}：快路径里只有仲裁要求结构化输出。改写与其它调用不是。
 * 这个判据将来若不成立（比如又多了一处 JSON 调用），录下来的会是最后一次——
 * 所以 {@link #arbitrationCalls()} 把次数也暴露出来，调用方可以断言它就是 1。
 */
public final class RecordingGateway implements ModelGatewayClient {

    private final ModelGatewayClient delegate;
    private ChatRequest lastArbitration;
    private int arbitrationCalls;

    public RecordingGateway(ModelGatewayClient delegate) {
        this.delegate = delegate;
    }

    /** 清空。每条用例跑之前调一次，否则录到的可能是上一条的。 */
    public void reset() {
        lastArbitration = null;
        arbitrationCalls = 0;
    }

    public Optional<ChatRequest> lastArbitration() {
        return Optional.ofNullable(lastArbitration);
    }

    public int arbitrationCalls() {
        return arbitrationCalls;
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public GatewayResult<List<float[]>> embed(List<String> inputs) {
        return delegate.embed(inputs);
    }

    @Override
    public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
        return delegate.rerank(query, documents, topN);
    }

    @Override
    public GatewayResult<String> chat(ChatRequest request) {
        if (request.jsonMode()) {
            lastArbitration = request;
            arbitrationCalls++;
        }
        return delegate.chat(request);
    }
}
