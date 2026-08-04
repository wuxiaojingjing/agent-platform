package com.huawei.finance.product.mobilebanking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.RouteDecision;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.contracts.port.TechDomainAgent;
import com.huawei.finance.cache.redis.RedisDecisionCache;
import com.huawei.finance.cache.redis.RedisDecisionCacheConfiguration;
import com.huawei.finance.contracts.port.GuardrailHook;
import com.huawei.finance.product.mobilebanking.console.EngineRegistry;
import com.huawei.finance.intent.IntentEngine;
import com.huawei.finance.intent.IntentEngineFactory;
import com.huawei.finance.intent.IntentRequest;
import com.huawei.finance.intent.IntentResult;
import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.GatewayResult;
import com.huawei.finance.gateway.ModelGatewayClient;
import com.huawei.finance.gateway.RerankHit;
import com.huawei.finance.obs.trace.DecisionTracePolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 对外承诺的单实现扩展点，在**完整应用上下文**里是否真的能被行内实现接管。
 *
 * <p>这组用例的存在理由，是 {@code ObsExtensionPointTest} 那种写法证不到的一件事。
 * 那边用 {@code ApplicationContextRunner} 只装一个自动配置，是个干净的实验室；
 * 真实应用里启动类还开着组件扫描，装配类若被扫成普通 {@code @Configuration} 会提前注册，
 * {@code @ConditionalOnMissingBean} 的让位语义就未必成立。实验室里能覆盖，
 * 不等于交到银行手上能覆盖。
 *
 * <p>而这类失效是**静默**的：行内的实现被忽略，系统照基线默认行为跑，没有任何报错。
 * 换护栏的没换上，等于合规规则没生效；换模型网关的没换上，等于还在打行外的模型。
 * 两者都得等到出事才发现，所以必须由用例守住。
 *
 * <p>覆盖之后本上下文的业务行为是废的（护栏全放行、网关是空壳），这里也不测业务——
 * 只断言 Bean 的归属。业务行为由各模块自己的用例负责。
 */
@SpringBootTest(properties = {
        // 索引构建会走网关，而本上下文的网关是空壳；这里只验装配归属，不必牵动索引
        "huawei.finance.agent.registry.build-index-on-startup=false",
        "huawei.finance.sample.mock.enabled=true",
        "spring.flyway.enabled=false"
})
class ExtensionPointOverrideTest {

    @Autowired
    private GuardrailHook guardrail;
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private ModelGatewayClient gateway;
    @Autowired
    private DecisionCache cache;
    @Autowired
    private DecisionTracePolicy tracePolicy;
    @Autowired
    private ObjectProvider<DomainAgent> agents;
    @Autowired
    private IntentEngineFactory intentEngineFactory;
    @Autowired
    private EngineRegistry engineRegistry;

    @Test
    @DisplayName("护栏：行内实现接管，基线 PolicyGuardrail 让位")
    void guardrailIsOverridable() {
        assertThat(guardrail).isInstanceOf(BankGuardrail.class);
    }

    @Test
    @DisplayName("Redisson：行内自建客户端接管，基线单机装配让位")
    void redissonIsOverridable() {
        assertThat(redisson).isSameAs(BankBeans.REDISSON);
    }

    @Test
    @DisplayName("模型网关：行内统一推理入口接管")
    void gatewayIsOverridable() {
        assertThat(gateway).isInstanceOf(BankGateway.class);
    }

    @Test
    @DisplayName("出口缓存：行内换掉 Redis 介质后，连 RedisDecisionCache 都不再构造")
    void decisionCacheIsOverridable() {
        assertThat(cache).isInstanceOf(BankCache.class);
    }

    @Test
    @DisplayName("Trace 策略：行内的脱敏口径接管")
    void tracePolicyIsOverridable() {
        assertThat(tracePolicy).isInstanceOf(BankTracePolicy.class);
        assertThat(tracePolicy.maxCandidates()).isEqualTo(1);
    }

    @Test
    @DisplayName("意图引擎：行内工厂接管，并作用于手机银行助手当前资产快照")
    void intentEngineFactoryIsOverridable() {
        assertThat(intentEngineFactory).isInstanceOf(BankIntentEngineFactory.class);
        assertThat(engineRegistry.current().intentEngine()).isInstanceOf(BankIntentEngine.class);
    }

    /**
     * {@code AgentInvoker} 取第一个 {@code supports} 为真的实现，所以同一能力上有两个实现时
     * 谁在前面就是谁执行。这个顺序**必须靠 {@code @Order} 显式声明**——不声明时
     * Spring 不承诺任何顺序，靠注册先后是碰运气。
     *
     * <p>生产环境里这个冲突本不该发生：Mock 默认关着（见 {@link MockAgentsOffByDefault}）。
     * 这条守的是开发期——本地开着 Mock 联调行内 Agent 时，跑的得是行内那个。
     */
    @Test
    @DisplayName("领域 Agent：同一能力上行内实现按 @Order 抢在 Mock 之前")
    void bankAgentTakesPrecedenceOverMock() {
        List<DomainAgent> ordered = agents.orderedStream()
                .filter(a -> a.supports("cap.account.balance.query"))
                .toList();
        assertThat(ordered).hasSizeGreaterThan(1);
        assertThat(ordered.getFirst()).isInstanceOf(BankAgent.class);
    }

    /**
     * 与 {@link MockAgentsOffByDefault#noMockAgentsWhenDisabled()} 成对。
     *
     * <p>缺了这一条，那边就是空转：嵌套上下文没装 {@code BankBeans}，Agent 列表本来就可能
     * 是空的，「没有 Mock」于是平凡成立——开关坏掉也照样绿。必须先证明开关打开时
     * Mock 确实在，「关掉后不在」才说明是开关起的作用。
     *
     * <p>断言的是**一域一 Agent 的双射**（附录 F 26 个科技域），而不是「有几个可执行 Mock」。
     * 原先写的是 {@code hasSize(3)}，后来 Mock 从 3 个加到 7 个，这条就红了——而它红下来
     * 说明不了任何问题，只说明有人加了 Mock。域总数是稳定的，可执行 Mock 与 Scaffold
     * 的配比会一直变（那正是「按域逐个替换」的过程）；把断言挂在会变的那个数上，
     * 结果是每次正常推进都要来改一次用例，改着就没人看它到底在验什么了。
     */
    @Test
    @DisplayName("开关打开时 26 个域各有一个 Agent（给关闭那条用例提供对照）")
    void mockAgentsPresentWhenSwitchOn() {
        assertThat(agents.orderedStream())
                .filteredOn(TechDomainAgent.class::isInstance)
                .as("26 个科技域应各有一个承接者：已交付领域实现或显式未开放的 Scaffold")
                .hasSize(26);
    }

    /**
     * Mock 领域 Agent 默认必须是关的。
     *
     * <p>这条是给错数据的防线，不是洁癖。{@code MockAccountAgent} 返回的是写死的
     * 「尾号 8821 可用余额 ¥12,845.60」。行内把基线拉过去、领域 Agent 还没接上时，
     * 若 Mock 恒定生效，客户问余额会得到一个结构完全正常、看不出异常的假数字。
     * 宁可「无人承接」这个明确失败。
     */
    @Nested
    @SpringBootTest(classes = {MobileBankingAssistantApplication.class, ExtensionPointRedisStub.class}, properties = {
            "huawei.finance.agent.registry.build-index-on-startup=false",
            "spring.flyway.enabled=false",
            "huawei.finance.sample.mock.enabled=false"
    })
    class MockAgentsOffByDefault {

        @Autowired
        private ObjectProvider<DomainAgent> agents;

        @Test
        @DisplayName("开关关掉后，三个 Mock Agent 一个都不在")
        void noMockAgentsWhenDisabled() {
            assertThat(agents.orderedStream().toList())
                    .noneMatch(a -> a.getClass().getPackageName().startsWith("com.huawei.finance.sample.mock"));
        }
    }

    /**
     * Mock 与真实 OJ 链路同时打开时，必须拒绝启动。
     *
     * <p>这不是配置洁癖。两者都在时，同一个能力有两个 {@code DomainAgent} 承接，
     * 而 {@code AgentInvoker} 取的是第一个——上面那条用例靠 {@code @Order} 才让行内实现稳定在前，
     * 而 Mock 与 OJ 两个自动配置之间没有任何顺序声明。也就是说这种配置下，
     * 一笔转账走真实通道还是走写死的假数据是不确定的，而走错的那侧会把从未发生的转账报成成功。
     *
     * <p>这种错配的隐蔽程度是它危险的原因：启动日志正常、指标正常、响应结构正常。
     * 所以只能不许它起来。
     */
    @Nested
    @SpringBootTest(classes = {MobileBankingAssistantApplication.class, ExtensionPointRedisStub.class},
            properties = {"huawei.finance.agent.registry.build-index-on-startup=false",
                    "huawei.finance.sample.mock.enabled=true", "spring.flyway.enabled=false"})
    class MockAndOjMustNotBothBeOn {

        @Test
        @DisplayName("mock 与 oj 同时开启时上下文起不来，且原因说得清")
        void refusesToStart() {
            assertThatThrownBy(() -> new SpringApplicationBuilder(
                    MobileBankingAssistantApplication.class, ExtensionPointRedisStub.class)
                    .web(WebApplicationType.NONE)
                    .run("--huawei.finance.agent.registry.build-index-on-startup=false",
                            "--spring.flyway.enabled=false",
                            "--huawei.finance.sample.mock.enabled=true",
                            "--huawei.finance.sample.openjiuwen.enabled=true",
                            "--huawei.finance.sample.openjiuwen.endpoints.cap.transfer=http://localhost:1")
                    .close())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不得同时为 true");
        }
    }

    /**
     * 生产那副姿态：OJ 开、Mock 关，上下文必须起得来。
     *
     * <p>这条不是「顺便也测一下」。上一条用例第一次跑时失败的真实原因不是守卫没触发，
     * 而是**上下文在触发守卫之前就因为别的原因炸了**：`ojRestClient` 与模型网关那个
     * `RestClient` 同类型，按类型注入的地方变成 `NoUniqueBeanDefinitionException`。
     * 也就是说当时的代码里，打开 OJ 会把模型网关搞坏，而报错信息跟 OJ 毫无关系。
     * 光有「两个都开要炸」那条用例是发现不了它的——炸了就算过。
     */
    @Nested
    @SpringBootTest(classes = {MobileBankingAssistantApplication.class, ExtensionPointRedisStub.class}, properties = {
            "huawei.finance.agent.registry.build-index-on-startup=false",
            "spring.flyway.enabled=false",
            "huawei.finance.sample.mock.enabled=false",
            "huawei.finance.sample.openjiuwen.enabled=true",
            "huawei.finance.sample.openjiuwen.endpoints.cap-transfer=http://localhost:19999"
    })
    class OjEnabledWithoutMock {

        @Autowired
        private ObjectProvider<DomainAgent> agents;

        @Autowired
        private ModelGatewayClient gateway;

        @Test
        @DisplayName("OJ 开、Mock 关：装上 OJ 领域 Agent，且模型网关没被带坏")
        void productionShapeStarts() {
            assertThat(agents.orderedStream())
                    .anyMatch(a -> a.getClass().getPackageName().startsWith("com.huawei.finance.sample.oj"));
            assertThat(agents.orderedStream())
                    .noneMatch(a -> a.getClass().getPackageName().startsWith("com.huawei.finance.sample.mock"));
            assertThat(gateway)
                    .as("模型网关能注入，说明多出来的那个 RestClient 没有污染按类型注入")
                    .isNotNull();
        }
    }

    /**
     * 无行内覆盖时，出口缓存必须是 Redis 那个实现——而不是引擎的「不缓存」默认值。
     *
     * <p>与 {@link #decisionCacheIsOverridable()} 成对，方向相反：那条验「换得掉」，
     * 这条验「不换时装的是对的那个」。缺了这条，让位链一旦反向就没有任何东西会红。
     *
     * <p>为什么这个方向也要守：{@code cache-redis} 提供 {@code RedisDecisionCache} Bean；
     * 快路径经 {@code ObjectProvider} 取缓存，无 Bean 时才回退 {@code DecisionCache.disabled()}。
     * 若引擎再抢先注册 disabled Bean，Redis 实现进不来——系统功能全对、一级命中率恒为 0。
     *
     * <p>这条用例对顺序是真敏感的：把那个标注改成 {@code @AutoConfigureAfter} 它就红
     * （已实测）。但要说清它守不住的那一半——**把标注整个删掉它仍然绿**，
     * 因为 Boot 无声明时按类名字母序排，{@code com.huawei.finance.cache} 恰在 {@code com.huawei.finance.fastpath} 之前。
     * 也就是说它能挡住「顺序写反」，挡不住「顺序丢失后靠字母序侥幸对上」。
     * 后者由 {@link #autoConfigureBeforeTargetResolves()} 补上。
     */
    @Nested
    @SpringBootTest(classes = {MobileBankingAssistantApplication.class, ExtensionPointRedisStub.class},
            properties = {"huawei.finance.agent.registry.build-index-on-startup=false",
                    "spring.flyway.enabled=false"})
    class RedisCacheWinsWhenNotOverridden {

        @Autowired
        private DecisionCache cache;

        @Test
        @DisplayName("没有行内覆盖时，装的是 Redis 实现而不是不缓存的默认值")
        void redisImplementationIsTheBaseline() {
            assertThat(cache)
                    .as("cache-redis 在 classpath 上且有 RedissonClient，"
                            + "它必须赢过引擎的 DecisionCache.disabled()")
                    .isInstanceOf(RedisDecisionCache.class);
        }
    }

    /**
     * {@code @AutoConfigureBefore(name = ...)} 里那个类名字符串必须解析得到真类。
     *
     * <p>为什么单立一条：拆出 {@code intent-engine-api} 之后 {@code cache-redis} 只依赖门面，
     * {@code FastPathConfiguration} 不在它的编译期 classpath 上，于是顺序声明只能写字符串。
     * 代价是丢了「类名写错编译不过」这层保护，而这个代价比它看起来贵——
     * 字符串写错时 Boot 解析不到就**当这条顺序不存在**，退回类名字母序，
     * 而字母序今天恰好也是对的（{@code com.huawei.finance.cache} 排在 {@code com.huawei.finance.fastpath} 前）。
     * 也就是说：写错了，{@link RedisCacheWinsWhenNotOverridden} 照样绿，
     * 缓存也照样是 Redis——直到哪天包名一改，一级命中率无声归零。
     *
     * <p>所以这里不验行为，验**字符串本身**。放在本模块是因为它同时依赖
     * {@code cache-redis} 与 {@code intent-fastpath}，两边的类都加载得到；
     * 放在 {@code cache-redis} 自己的测试里反而做不到——那正是拆门面要换来的东西。
     *
     * <p>顺带断言标注在场且 {@code name} 非空：不断这两条的话，把标注整个删掉本用例
     * 就会因为「没有要检查的字符串」而空跑通过，等于把闸门关掉。
     */
    @Test
    @DisplayName("cache-redis 的 @AutoConfigureBefore 字符串解析得到真类")
    void autoConfigureBeforeTargetResolves() {
        var annotation = RedisDecisionCacheConfiguration.class
                .getAnnotation(AutoConfigureBefore.class);

        assertThat(annotation)
                .as("RedisDecisionCacheConfiguration 上的 @AutoConfigureBefore 不见了，"
                        + "让位顺序就只剩字母序这个巧合在撑")
                .isNotNull();
        assertThat(annotation.name())
                .as("name 空了等于没声明顺序；类引用这条路已经走不通（fastpath 不在编译期 classpath 上）")
                .isNotEmpty();

        for (String className : annotation.name()) {
            assertThatCode(() -> Class.forName(className))
                    .as(className + " 解析不到。Boot 不会为此报错，"
                            + "它会当这条顺序不存在然后退回字母序——今天字母序恰好也对，"
                            + "所以写错了没有任何用例会红")
                    .doesNotThrowAnyException();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BankBeans {

        /** 与基线同样连本地 Redis，但实例是这里建的——身份可辨，才能断言谁胜出。 */
        static final RedissonClient REDISSON = createRedisson();

        private static RedissonClient createRedisson() {
            return redissonStub();
        }

        @Bean(destroyMethod = "shutdown")
        RedissonClient bankRedisson() {
            return REDISSON;
        }

        @Bean
        GuardrailHook bankGuardrail() {
            return new BankGuardrail();
        }

        @Bean
        ModelGatewayClient bankGateway() {
            return new BankGateway();
        }

        @Bean
        DecisionCache bankCache() {
            return new BankCache();
        }

        @Bean
        DecisionTracePolicy bankTracePolicy() {
            return new BankTracePolicy();
        }

        @Bean
        IntentEngineFactory bankIntentEngineFactory() {
            return new BankIntentEngineFactory();
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        DomainAgent bankAgent() {
            return new BankAgent();
        }
    }

    static RedissonClient redissonStub() {
        return (RedissonClient) Proxy.newProxyInstance(
                RedissonClient.class.getClassLoader(), new Class<?>[]{RedissonClient.class},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (!type.isPrimitive()) {
                        return null;
                    }
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == char.class) {
                        return '\0';
                    }
                    return 0;
                });
    }

    static class BankGuardrail implements GuardrailHook {
        @Override
        public GuardrailCheck check(UnifiedTask draft, CapabilityCard card) {
            return GuardrailCheck.passed();
        }
    }

    /** 空壳网关：{@code available()} 为假，各消费方按既有的退化路径处理。 */
    static class BankGateway implements ModelGatewayClient {
        @Override
        public GatewayResult<List<float[]>> embed(List<String> inputs) {
            return GatewayResult.unavailable("BANK_STUB", 0L);
        }

        @Override
        public GatewayResult<String> chat(ChatRequest request) {
            return GatewayResult.unavailable("BANK_STUB", 0L);
        }

        @Override
        public GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN) {
            return GatewayResult.unavailable("BANK_STUB", 0L);
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    /** 进程内缓存：代表「监管不允许决策结论出进程」那类行内约束。 */
    static class BankCache implements DecisionCache {
        private final Map<String, RouteDecision> store = new ConcurrentHashMap<>();

        @Override
        public Optional<RouteDecision> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(String key, RouteDecision decision) {
            store.put(key, decision);
        }
    }

    static class BankTracePolicy implements DecisionTracePolicy {
        @Override
        public int maxCandidates() {
            return 1;
        }

        @Override
        public boolean includeEvidence() {
            return false;
        }

        @Override
        public boolean includeSuppressed() {
            return false;
        }
    }

    static class BankIntentEngineFactory implements IntentEngineFactory {
        @Override
        public IntentEngine create(IntentEngine platformDefault) {
            return new BankIntentEngine(platformDefault);
        }
    }

    /** 保留平台全部识别能力，只示范在稳定门面外增加一层业务流程。 */
    static class BankIntentEngine implements IntentEngine {
        private final IntentEngine delegate;

        BankIntentEngine(IntentEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public IntentResult recognize(IntentRequest request) {
            return delegate.recognize(request);
        }
    }

    static class BankAgent implements DomainAgent {
        @Override
        public boolean supports(String capabilityId) {
            return "cap.account.balance.query".equals(capabilityId);
        }

        @Override
        public TaskResult execute(UnifiedTask task) {
            return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                    Map.of("availableBalance", "行内实现"), task.idempotencyKey(),
                    task.guardrailCheck());
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class ExtensionPointRedisStub {
    @Bean
    RedissonClient testRedissonClient() {
        return ExtensionPointOverrideTest.redissonStub();
    }
}
