package com.huawei.finance.registry.index;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.huawei.finance.registry.asset.AssetBundle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.update_aliases.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 能力卡索引构建器。
 *
 * <p>采用「建新索引 → 灌数据 → 切别名」的方式，不做原地更新。原地更新在灌数据期间
 * 索引处于半新半旧状态，而召回是随时在跑的；切别名是原子操作，不存在中间态。
 *
 * <p>向量在这里**离线批量**生成，不在请求路径上算文档向量：文档侧向量是静态的，
 * 每次请求重算既浪费网关往返，又会让 A 线的 ≤2 次约束直接破产。
 */
public class CapabilityIndexer {

    private static final Logger log = LoggerFactory.getLogger(CapabilityIndexer.class);

    /** 每批向量化的文档数。批量能摊薄往返开销，过大则单次超时风险上升。 */
    private static final int EMBED_BATCH_SIZE = 16;

    private final OpenSearchClient client;
    private final ModelGatewayClient gateway;
    private final ModelGatewayProperties modelProps;
    private final RegistryProperties props;
    private final IndexReadiness readiness;

    public CapabilityIndexer(OpenSearchClient client, ModelGatewayClient gateway,
                             ModelGatewayProperties modelProps, RegistryProperties props,
                             IndexReadiness readiness) {
        this.client = client;
        this.gateway = gateway;
        this.modelProps = modelProps;
        this.props = props;
        this.readiness = readiness;
    }

    /**
     * 为给定资产版本构建索引并切换别名。
     *
     * @return 是否写入了向量。未写入时语义通道不可用，但 BM25 仍可服务
     */
    public boolean rebuild(AssetBundle bundle) throws IOException {
        String indexName = indexNameFor(bundle.assetVersion());
        readiness.markBuilding();

        if (client.indices().exists(e -> e.index(indexName)).value()) {
            log.info("索引已存在，直接切别名 index={}", indexName);
        } else {
            createIndex(indexName);
        }

        List<CapabilityCard> cards = bundle.recallableCapabilities();
        List<float[]> vectors = embedAll(cards);
        boolean vectorsIndexed = vectors != null;

        bulkIndex(indexName, cards, vectors);
        client.indices().refresh(r -> r.index(indexName));
        switchAlias(indexName);

        readiness.markReady(indexName, bundle.assetVersion(), vectorsIndexed, cards.size());
        log.info("索引就绪 index={} 文档数={} 含向量={}", indexName, cards.size(), vectorsIndexed);
        return vectorsIndexed;
    }

    /**
     * 索引名包含资产版本与向量模型/指令模板版本。
     *
     * <p>指令模板一变，同一段文本的向量就完全不同，旧向量与新 query 向量不在同一空间里
     * 比较毫无意义（实施架构 §2.5.6 落地约束 1）。把这两个版本写进索引名，
     * 换指令必然换索引，不会出现新旧向量混在一个索引里的情况。
     */
    String indexNameFor(String assetVersion) {
        String modelTag = sanitize(modelProps.getEmbedding().getModel());
        String instructionTag = sanitize(modelProps.getEmbedding().getInstructionVersion());
        return String.join("-", props.getIndexPrefix(), sanitize(assetVersion), modelTag, instructionTag);
    }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private void createIndex(String indexName) throws IOException {
        int dims = modelProps.getEmbedding().getDimensions();

        CreateIndexRequest request = new CreateIndexRequest.Builder()
                .index(indexName)
                .settings(IndexSettings.of(s -> s
                        .knn(true)
                        .numberOfShards("1")
                        .numberOfReplicas("0")))
                .mappings(m -> m
                        .properties("capabilityId", Property.of(p -> p.keyword(k -> k)))
                        // cjk 分析器做二元切分。默认的 standard 分析器对中文是逐字切分，
                        // 「余额」会被拆成「余」「额」，与「余款」「金额」大量误匹配。
                        // cjk 是内置分析器，不需要额外插件，是这里能拿到的最好选择。
                        .properties("name", Property.of(p -> p.text(t -> t.analyzer("cjk"))))
                        .properties("description", Property.of(p -> p.text(t -> t.analyzer("cjk"))))
                        .properties("searchText", Property.of(p -> p.text(t -> t.analyzer("cjk"))))
                        .properties("keywords", Property.of(p -> p.keyword(k -> k)))
                        .properties("domains", Property.of(p -> p.keyword(k -> k)))
                        .properties("capabilityType", Property.of(p -> p.keyword(k -> k)))
                        .properties("riskLevel", Property.of(p -> p.keyword(k -> k)))
                        .properties("requiredSlots", Property.of(p -> p.keyword(k -> k)))
                        .properties("version", Property.of(p -> p.keyword(k -> k)))
                        .properties("vector", Property.of(p -> p.knnVector(v -> v
                                .dimension(dims)
                                // 余弦相似度：文本向量比较的是方向而非长度
                                .spaceType("cosinesimil")))))
                .build();

        client.indices().create(request);
        log.info("创建索引 index={} 向量维度={}", indexName, dims);
    }

    /**
     * 批量生成文档向量。
     *
     * @return 与入参等长的向量列表；网关不可用时返回 null，调用方据此把索引标为无向量
     */
    private List<float[]> embedAll(List<CapabilityCard> cards) {
        if (!gateway.available()) {
            log.warn("模型网关不可用，索引将不含向量，语义召回通道摘除");
            return null;
        }
        List<float[]> all = new ArrayList<>(cards.size());
        for (int from = 0; from < cards.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(from + EMBED_BATCH_SIZE, cards.size());
            List<String> texts = cards.subList(from, to).stream()
                    // 文档侧不拼检索指令，只有 query 侧拼
                    .map(CapabilityCard::embeddingDocument)
                    .toList();
            GatewayResult<List<float[]>> result = gateway.embed(texts);
            if (!result.available()) {
                log.warn("批量向量化失败，整个索引降级为无向量 batch=[{},{}) reason={}",
                        from, to, result.reason());
                return null;
            }
            all.addAll(result.value());
        }
        return all;
    }

    private void bulkIndex(String indexName, List<CapabilityCard> cards, List<float[]> vectors)
            throws IOException {
        List<BulkOperation> ops = new ArrayList<>(cards.size());
        for (int i = 0; i < cards.size(); i++) {
            CapabilityCard card = cards.get(i);
            CapabilityDocument doc = CapabilityDocument.from(card, vectors == null ? null : vectors.get(i));
            ops.add(BulkOperation.of(b -> b.index(idx -> idx
                    .index(indexName)
                    .id(card.capabilityId())
                    .document(doc))));
        }
        BulkResponse response = client.bulk(BulkRequest.of(b -> b.operations(ops)));
        if (response.errors()) {
            String firstError = response.items().stream()
                    .filter(it -> it.error() != null)
                    .map(it -> it.id() + ": " + it.error().reason())
                    .findFirst()
                    .orElse("未知");
            throw new IllegalStateException("能力卡写入索引失败，首个错误：" + firstError);
        }
    }

    /**
     * 原子切换别名，并清理旧索引的别名绑定。
     *
     * <p>remove + add 放在同一个 updateAliases 请求里，OpenSearch 保证原子生效，
     * 中间不存在别名指向零个或两个索引的瞬间。
     */
    private void switchAlias(String indexName) throws IOException {
        List<Action> actions = new ArrayList<>();
        boolean aliasExists = client.indices().existsAlias(a -> a.name(props.getAlias())).value();
        if (aliasExists) {
            actions.add(Action.of(a -> a.remove(r -> r.index("*").alias(props.getAlias()))));
        }
        actions.add(Action.of(a -> a.add(add -> add.index(indexName).alias(props.getAlias()))));
        client.indices().updateAliases(u -> u.actions(actions));
        log.info("别名切换完成 alias={} -> index={}", props.getAlias(), indexName);
    }
}
