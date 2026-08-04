package com.huawei.finance.sample.oj;

import com.huawei.finance.contracts.port.DomainAgent;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.orchestrator.DefaultServeOrchestrator;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 行内 Agent Server 进程侧的装配：把 {@link OpenJiuwenAgentHandler} 挂进 OpenJiuwen 的服务骨架。
 *
 * <p>它装在**领域方那个部署件**里，不是中控进程里。中控侧是 {@link OjAgentConfiguration}，
 * 两侧刻意分开成两个装配类：同一个进程里同时开这两个开关，等于自己调自己，
 * 而那种环形调用在压测时才会暴露。
 *
 * <h2>为什么要自己声明 ServeOrchestrator</h2>
 *
 * <p>{@link AgentServiceAutoConfiguration} 提供了生命周期、就绪探针、控制器扫描，
 * 却**没有** {@link ServeOrchestrator} 的 Bean——控制器是用 {@code ObjectProvider} 取它的，
 * 缺了不会启动失败，而是每次请求返回 503。所以这里补一个 {@link DefaultServeOrchestrator}，
 * 让「没装编排器」在启动期就能看出来，而不是等第一笔请求打进来。
 *
 * <p>声明 {@code before = AgentServiceAutoConfiguration.class} 是必需的：那边的
 * {@code agentHandlerHolder} 带 {@code @ConditionalOnMissingBean(AgentHandler.class)}，
 * 要让它看见我们这个 Handler，本类必须先跑。
 */
@AutoConfiguration(before = AgentServiceAutoConfiguration.class)
@ConditionalOnClass(DefaultServeOrchestrator.class)
@ConditionalOnProperty(name = "huawei.finance.sample.openjiuwen.server.enabled", havingValue = "true")
public class OpenJiuwenAgentServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenJiuwenAgentServerConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public OjQueryCodec ojQueryCodec() {
        return new OjQueryCodec();
    }

    /**
     * 没有任何 {@link DomainAgent} 时也建 Bean，但把话说清楚。
     *
     * <p>不在这里 fail-fast：Agent Server 起不来时中控看到的是连接失败（判 RETRYABLE，
     * 会重试）；而起得来但没有实现时中控看到的是明确的 {@code NO_AGENT_FOR_CAPABILITY}
     * （判 FATAL，不重试）。后者才是这个部署状态的真实含义。
     */
    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public AgentHandler openJiuwenAgentHandler(List<DomainAgent> agents, OjQueryCodec codec) {
        if (agents.isEmpty()) {
            log.error("Agent Server 已启用但进程里没有任何 DomainAgent 实现，"
                    + "所有请求都会以 NO_AGENT_FOR_CAPABILITY 失败");
        } else {
            log.info("行内 AgentHandler 已装配，DomainAgent 实现数：{}", agents.size());
        }
        return new OpenJiuwenAgentHandler(agents, codec);
    }

    @Bean
    @ConditionalOnMissingBean(ServeOrchestrator.class)
    public ServeOrchestrator openJiuwenServeOrchestrator(AgentHandler handler, ActiveStreamRegistry registry) {
        return new DefaultServeOrchestrator(handler, registry);
    }
}
