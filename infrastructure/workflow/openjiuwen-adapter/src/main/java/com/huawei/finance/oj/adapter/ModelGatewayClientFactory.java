package com.huawei.finance.oj.adapter;

import com.huawei.finance.gateway.ModelGatewayClient;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;

/**
 * 把本工程的模型网关注册成 OpenJiuwen 的一个模型 provider。
 *
 * <p>存在的理由是「让 OJ 侧的组件也走我们这条通道」，而不是让它们各自直连厂商。
 * OJ 的 LLM 组件、ReActAgent 只认 {@code clientProvider} 这个名字；配成 {@value #PROVIDER}
 * 之后，它们发出的每一次调用都会落到 {@link com.huawei.finance.gateway.BudgetAwareModelGateway}
 * 装饰过的那个 bean 上——往返预算（FP-1E）、熔断、审计因此对 OJ 侧同样生效。
 * 若让 OJ 用它自带的 OpenAI/SiliconFlow 客户端，这三样会被整条绕过，
 * 而绕过时没有任何报错，只是预算这道硬约束在 OJ 那一侧不存在。
 *
 * <p><b>为什么有一个静态绑定。</b>OJ 用 {@link java.util.ServiceLoader} 实例化工厂，
 * 要求无参构造，拿不到 Spring 容器。因此由 {@link OjAdapterConfiguration} 在启动时
 * 把网关 bean 绑进来。静态可变状态在这里是被迫的，代价用两条约束压住：
 * 绑定只允许发生在装配期，未绑定时创建客户端**当场抛错**而不是回落到别的 provider——
 * 静默回落意味着请求照常成功、账单照常产生，只是没人在管预算。
 */
public class ModelGatewayClientFactory implements Model.ModelClientFactory {

    /** 配置里写这个名字才会用上本通道。 */
    public static final String PROVIDER = "agent-platform";

    /**
     * 占位凭据。
     *
     * <p>OJ 的 {@link ModelClientConfig} 在构造期就要求 apiKey 与 apiBase 非空
     * （{@code Objects.requireNonNull}），而本通道一个凭据都不持有——真密钥只在
     * {@link ModelGatewayClient} 的实现里。占位值写成一眼能看出是假的，
     * 是为了避免有人在排查时把它当成配错了的真密钥去追。
     */
    private static final String PLACEHOLDER_KEY = "unused-credentials-live-in-agent-platform-gateway";
    private static final String PLACEHOLDER_BASE = "huawei-finance-agent://model-gateway";

    /**
     * 构造一份指向本通道的客户端配置。
     *
     * <p>给使用方用，省得每处都自己想 apiKey 该填什么——自己想的结果通常是把真密钥
     * 又抄一份进配置文件。
     */
    public static ModelClientConfig clientConfig() {
        return ModelClientConfig.builder()
                .clientProvider(PROVIDER)
                .apiKey(PLACEHOLDER_KEY)
                .apiBase(PLACEHOLDER_BASE)
                .build();
    }

    private static volatile ModelGatewayClient gateway;

    /** 由装配期调用。重复绑定按后者为准，便于测试替换。 */
    static void bind(ModelGatewayClient client) {
        gateway = client;
    }

    /** 供启动自检用：ServiceLoader 到底有没有发现本工厂。 */
    public static boolean bound() {
        return gateway != null;
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
        ModelGatewayClient client = gateway;
        if (client == null) {
            throw new IllegalStateException(
                    "OJ 请求了 " + PROVIDER + " 模型通道，但网关尚未绑定。"
                            + "多半是 openjiuwen 的自动配置没生效，"
                            + "或在 Spring 上下文就绪前就构造了 OJ 的 Model");
        }
        return new GatewayBackedModelClient(modelConfig, clientConfig, client);
    }
}
