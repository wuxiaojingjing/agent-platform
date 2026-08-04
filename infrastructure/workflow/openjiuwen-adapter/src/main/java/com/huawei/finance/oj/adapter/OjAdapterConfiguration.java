package com.huawei.finance.oj.adapter;

import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.ModelGatewayProperties;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.retrieval.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 把本工程的实现注册进 OpenJiuwen 的扩展点。
 *
 * <p>无条件生效，没有开关：它只是**注册**，注册本身不发起任何调用。给它加个开关的坏处是，
 * 关着的时候 OJ 侧会静默回落到自带的直连客户端——那条路径绕过往返预算与熔断，
 * 而且不报错。真正的开关是配置里写不写 {@code clientProvider: agent-platform}。
 */
@AutoConfiguration
public class OjAdapterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OjAdapterConfiguration.class);

    /**
     * 把网关绑给 ServiceLoader 造出来的那个工厂，并再编程式注册一次。
     *
     * <p>两条注册路径都要：`META-INF/services` 在 fat jar 里被覆盖或漏合并是常见坑，
     * 而它的失败方式是静默的——OJ 找不到 {@code agent-platform} 这个 provider 时按名字回落，
     * 请求照常成功，只是预算与熔断不在了。编程式注册在这里是那道兜底。
     */
    @Bean
    @ConditionalOnBean(ModelGatewayClient.class)
    public ModelGatewayProviderRegistrar agentModelProviderRegistrar(ModelGatewayClient gateway) {
        ModelGatewayClientFactory.bind(gateway);
        Model.registerFactory(new ModelGatewayClientFactory());
        log.info("已把模型网关注册为 OpenJiuwen 模型通道 provider={}", ModelGatewayClientFactory.PROVIDER);
        return new ModelGatewayProviderRegistrar();
    }

    /**
     * OJ 检索侧的向量化实现。
     *
     * <p>让位给使用方：行内若已有统一的向量化服务，声明同类型 Bean 即可接管。
     */
    @Bean
    @ConditionalOnBean(ModelGatewayClient.class)
    @ConditionalOnMissingBean(Embedding.class)
    public Embedding agentEmbedding(ModelGatewayClient gateway, ModelGatewayProperties props) {
        return new ModelGatewayEmbedding(gateway, props);
    }

    /** 注册动作的完成凭据。存在这个 Bean 表示注册跑过了，供启动自检与测试断言。 */
    public static final class ModelGatewayProviderRegistrar {
    }
}
