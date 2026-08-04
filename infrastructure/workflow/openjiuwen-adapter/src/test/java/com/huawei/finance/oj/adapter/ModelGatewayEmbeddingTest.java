package com.huawei.finance.oj.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.gateway.ModelGatewayProperties;
import com.openjiuwen.core.retrieval.embedding.Embedding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelGatewayEmbeddingTest {

    private static ModelGatewayProperties props() {
        ModelGatewayProperties props = new ModelGatewayProperties();
        props.getEmbedding().setDimensions(1024);
        return props;
    }

    @Test
    @DisplayName("查询侧拼指令、文档侧不拼——这条不对称一旦被「统一」，召回质量会无声下滑")
    void queryIsInstructedButDocumentsAreNot() {
        RecordingGateway gateway = new RecordingGateway();
        Embedding embedding = new ModelGatewayEmbedding(gateway, props());

        embedding.embedQuery("卡里还有多少钱");
        embedding.embedDocuments(List.of("查询账户余额"), null);

        assertThat(gateway.embedInputs.get(0)).containsExactly(
                "Instruct: Given a user request in a mobile banking application, retrieve the most "
                        + "relevant banking capability that can fulfill the request\nQuery:卡里还有多少钱");
        assertThat(gateway.embedInputs.get(1))
                .as("文档侧拼上指令后，索引里的向量与查询向量不再可比，而这不会报错")
                .containsExactly("查询账户余额");
        assertThat(props().getEmbedding().getInstructionVersion()).isEqualTo("emb-instruct-v2");
    }

    @Test
    @DisplayName("按批切分，批大小缺省时一次发完")
    void documentsAreBatched() {
        RecordingGateway gateway = new RecordingGateway();
        Embedding embedding = new ModelGatewayEmbedding(gateway, props());

        List<List<Float>> vectors = embedding.embedDocuments(List.of("a", "b", "c"), 2);

        assertThat(vectors).hasSize(3);
        assertThat(gateway.embedInputs).hasSize(2);
        assertThat(gateway.embedInputs.get(0)).containsExactly("a", "b");
        assertThat(gateway.embedInputs.get(1)).containsExactly("c");
    }

    @Test
    @DisplayName("维度取自配置，与索引名里的那份是同一个来源")
    void dimensionComesFromConfiguration() {
        assertThat(new ModelGatewayEmbedding(new RecordingGateway(), props()).getDimension()).isEqualTo(1024);
    }

    @Test
    @DisplayName("网关不可用时抛错，不返回零向量")
    void unavailableGatewayFailsInsteadOfReturningZeroVectors() {
        Embedding embedding = new ModelGatewayEmbedding(new RecordingGateway(false, ""), props());

        assertThatThrownBy(() -> embedding.embedQuery("查余额"))
                .as("零向量与谁都不像，结果是「检索到了，但全是无关项」，比直接失败更难查")
                .isInstanceOf(IllegalStateException.class);
    }
}
