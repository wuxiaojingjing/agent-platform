package com.huawei.finance.sample.oj;

import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import com.huawei.finance.contracts.port.DomainAgent;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 中控侧接入远端 OpenJiuwen Agent Server 的装配。
 *
 * <p>与 Mock 那个模块同样默认关闭（{@code huawei.finance.sample.openjiuwen.enabled})。开着而后端不通时，
 * 每一次执行都要等到超时才失败，那个延迟会直接顶满面客链路的时间预算——
 * 「没配就没有这条链路」比「配错了慢慢超时」好查。
 */
@AutoConfiguration
@ConditionalOnProperty(name = "huawei.finance.sample.openjiuwen.enabled", havingValue = "true")
@EnableConfigurationProperties(OjProperties.class)
public class OjAgentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OjAgentConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public OjQueryCodec ojQueryCodec() {
        return new OjQueryCodec();
    }

    /**
     * 专用 {@link RestClient}，不复用应用里可能存在的通用 Builder。
     *
     * <p>关键是超时必须由这里定：{@link RestClient} 默认不设读超时，一次卡住的调用会把
     * 执行线程一直占着。这条链路的调用方是面客请求，它有自己的时间预算，
     * 没有超时的下游等于把那个预算交给对方决定。
     *
     * <p>{@code defaultCandidate = false} 是必需的，不是讲究。中控进程里已经有别的
     * {@link RestClient}（模型网关那个），再多一个同类型的 Bean 会让所有**按类型**注入
     * {@link RestClient} 的地方变成 {@code NoUniqueBeanDefinitionException}——
     * 也就是说打开 OJ 会把模型网关搞坏，而报错信息里根本看不出跟 OJ 有关。
     * 标成非默认候选之后它只能按名字注入，装上这条链路不再影响任何既有注入点。
     * （这条是 {@code ExtensionPointOverrideTest} 里那个「两个开关不许同时开」的用例
     * 顺手抓出来的：那次上下文起不来的真实原因就是这个冲突。）
     */
    @Bean(name = "ojRestClient", defaultCandidate = false)
    @ConditionalOnMissingBean(name = "ojRestClient")
    public RestClient ojRestClient(OjProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(properties.getRequestTimeoutMs()));
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 地址从哪来。
     *
     * <p>有注册中心接进来时（{@code discovery-nacos} 会注册一个 {@link AgentEndpointResolver}），
     * 静态路由表退居兜底，由那个实现自己决定；没有时就用配置里的表。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentEndpointResolver ojAgentEndpointResolver(OjProperties properties) {
        return AgentEndpointResolver.ofStatic(properties.getEndpoints());
    }

    /**
     * 一个能力都解析不出来时要吵。
     *
     * <p>否则会出现一个 {@code supports()} 恒为 false 的 Agent，从中控看是「无人承接」，
     * 但看日志会以为 OJ 已经接上了。宁可启动时就把「开了却没地址」这件事说出来。
     * 注意接了注册中心之后这里为空**未必是错**：领域进程可能只是还没上线，
     * 所以措辞与静态表那种「配漏了」区分开。
     */
    @Bean
    @ConditionalOnMissingBean(OjDomainAgent.class)
    public DomainAgent ojDomainAgent(@Qualifier("ojRestClient") RestClient ojRestClient,
                                     OjQueryCodec codec, AgentEndpointResolver endpoints) {
        var known = endpoints.knownCapabilities();
        if (known.isEmpty() && "static".equals(endpoints.source())) {
            log.error("huawei.finance.sample.openjiuwen.enabled=true 但 huawei.finance.sample.openjiuwen.endpoints 是空的，"
                    + "不会有任何能力被 OJ 承接。请按 能力标识: 服务地址 配置路由表");
        } else {
            log.info("OJ 领域 Agent 已接入，地址来源={} 当前可承接能力={}", endpoints.source(), known);
        }
        return new OjDomainAgent(ojRestClient, codec, endpoints);
    }
}
