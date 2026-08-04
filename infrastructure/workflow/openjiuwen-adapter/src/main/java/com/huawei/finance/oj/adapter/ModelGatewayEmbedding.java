package com.huawei.finance.oj.adapter;

import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.openjiuwen.core.retrieval.embedding.Embedding;
import java.util.ArrayList;
import java.util.List;

/**
 * OJ 的向量化接口，落到本工程的模型网关。
 *
 * <p>接进来是为了让 OJ 的检索组件与我们用同一套向量：维度、模型版本、指令模板必须一致，
 * 否则同一批能力卡会有两份互不可比的向量，而不一致的表现是「召回质量莫名变差」，
 * 不是报错。
 */
public class ModelGatewayEmbedding implements Embedding {

    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties props;

    public ModelGatewayEmbedding(ModelGatewayClient gateway, ModelGatewayProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    /**
     * 查询侧向量化：**拼检索指令**。
     *
     * <p>Qwen3-Embedding 是 instruction-aware 的，query 拼指令、文档不拼（实施架构 §2.5.6
     * 落地约束 1）。这条不对称看着像疏漏，很容易被下一个人「统一」掉——两侧都拼或都不拼，
     * 检索仍然能跑、也不报错，只是命中率整体下滑一截，而那时没人会想到是这里。
     * {@code ModelGatewayEmbeddingTest} 用一条断言把这个差异钉住。
     */
    @Override
    public List<Float> embedQuery(String text) {
        String instructed = props.getEmbedding().formatQuery(text);
        List<List<Float>> vectors = embed(List.of(instructed));
        return vectors.get(0);
    }

    /**
     * 文档侧向量化：**不拼指令**，理由同上。
     *
     * <p>入参是 {@code List<?>}（OJ 的签名如此），非字符串元素按 {@code toString} 处理——
     * 多模态文档在本通道里没有意义，与其在这里悄悄跳过，不如按文本送出去让它明显不对。
     */
    @Override
    public List<List<Float>> embedDocuments(List<?> texts, Integer batchSize) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> asText = texts.stream().map(String::valueOf).toList();
        int size = batchSize == null || batchSize <= 0 ? asText.size() : batchSize;
        List<List<Float>> all = new ArrayList<>(asText.size());
        for (int from = 0; from < asText.size(); from += size) {
            all.addAll(embed(asText.subList(from, Math.min(from + size, asText.size()))));
        }
        return all;
    }

    @Override
    public int getDimension() {
        return props.getEmbedding().getDimensions();
    }

    /**
     * 网关不可用时抛异常。
     *
     * <p>与快路径的处置刻意不同：快路径把 embedding 失败当常态，摘掉语义通道降级继续
     * （README 约束 6）。但 OJ 的 {@link Embedding} 契约没有「本次不可用」这个返回形态，
     * 返回空列表或零向量会让调用方拿它去算相似度——零向量与谁都不像，
     * 结果是「检索到了，但全是无关项」，比直接失败更难查。
     */
    private List<List<Float>> embed(List<String> inputs) {
        GatewayResult<List<float[]>> result = gateway.embed(inputs);
        if (!result.available() || result.value() == null) {
            throw new IllegalStateException("模型网关向量化不可用：" + result.reason());
        }
        List<List<Float>> converted = new ArrayList<>(result.value().size());
        for (float[] vector : result.value()) {
            List<Float> boxed = new ArrayList<>(vector.length);
            for (float v : vector) {
                boxed.add(v);
            }
            converted.add(List.copyOf(boxed));
        }
        return converted;
    }
}
