package com.huawei.finance.nacos.config;

import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.alibaba.nacos.api.exception.NacosException;
import com.huawei.finance.nacos.NacosProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * 订阅启动时导入过的那些 dataId，按白名单把变化落到 Environment 上。
 *
 * <h2>没有 @RefreshScope，也不打算有</h2>
 *
 * <p>Spring Cloud 那套是销毁并重建 Bean。它能覆盖的场景更广，代价是**重建时机不可控**：
 * 一个正在处理面客请求的组件可能在半途被换掉。这里改成只重新绑定
 * {@code @ConfigurationProperties} 对象本身，Bean 引用不变，代价是「启动时把值抄进
 * 自己字段里的组件」感知不到——而这正是白名单存在的理由：能进白名单的键，
 * 必须是有人确认过消费方每次都读的。
 *
 * <p>所以这里对三类键的处置是不同的三条日志，而不是一条「已刷新」：应用了哪些、
 * 按策略拒了哪些、白名单外忽略了哪些。运维在 Nacos 上点了发布之后，
 * 能不能从日志里看出「我这次改的到底算不算数」，决定了这套东西可不可信。
 */
public class NacosConfigRefresher {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigRefresher.class);

    private final ConfigurableEnvironment environment;
    private final ApplicationContext applicationContext;
    private final NacosConfigServiceFactory factory;
    private final HotReloadPolicy policy;
    private final List<String> refreshable;

    public NacosConfigRefresher(
            ConfigurableEnvironment environment,
            ApplicationContext applicationContext,
            NacosConfigServiceFactory factory,
            NacosProperties properties) {
        this.environment = environment;
        this.applicationContext = applicationContext;
        this.factory = factory;
        this.refreshable = List.copyOf(properties.getConfig().getRefreshable());
        this.policy = new HotReloadPolicy(this.refreshable);
    }

    /** 对启动时真正导入成功的每个 dataId 挂一个监听器。 */
    public void start() throws NacosException {
        List<String> watched = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            String name = source.getName();
            int at = name.indexOf("nacos:");
            if (at < 0) {
                continue;
            }
            // 名字形如 nacos:GROUP/dataId，可能被 Boot 加了后缀
            String spec = name.substring(at + "nacos:".length());
            int slash = spec.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String group = spec.substring(0, slash);
            String dataId = trimSuffix(spec.substring(slash + 1));
            factory.get().addListener(dataId, group, new AbstractListener() {
                @Override
                public Executor getExecutor() {
                    // 用 nacos 自己的回调线程。这里的活是解析文本与重绑定几个对象，
                    // 不做 IO，另起线程池只会多一处要关的资源
                    return null;
                }

                @Override
                public void receiveConfigInfo(String content) {
                    apply(name, dataId, content);
                }
            });
            watched.add(group + "/" + dataId);
        }
        if (watched.isEmpty()) {
            log.info("没有从 Nacos 导入任何配置，热更新监听未启动");
        } else {
            log.info("已监听 Nacos 配置变更 {}，热更新白名单前缀 {}", watched, refreshable);
        }
    }

    void apply(String sourceName, String dataId, String content) {
        Map<String, Object> after = parse(sourceName, dataId, content);
        if (after == null) {
            return;
        }
        PropertySource<?> current = environment.getPropertySources().get(sourceName);
        Map<String, Object> before = snapshot(current);

        HotReloadPolicy.Plan plan = policy.plan(before, after);
        if (plan.isEmpty()) {
            log.info("Nacos 推送 {}，内容无变化", dataId);
            return;
        }
        if (!plan.rejected().isEmpty()) {
            log.warn("Nacos 推送 {} 里这些键**不允许**热更新，已忽略（改连接参数或 spring.* 需重启）：{}",
                    dataId, plan.rejected());
        }
        if (!plan.ignored().isEmpty()) {
            log.warn("Nacos 推送 {} 里这些键不在热更新白名单内，**本次不生效**，重启后才会生效：{}",
                    dataId, plan.ignored());
        }
        if (plan.applied().isEmpty()) {
            return;
        }

        Map<String, Object> merged = new LinkedHashMap<>(before);
        plan.applied().forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        // 原地替换，保住 ConfigData 当初给它的优先级：命令行与环境变量仍然压得住它。
        // 若改成往最前面插一个新 source，配置中心就悄悄变成了最高优先级，
        // 出事时运维用环境变量当场压值这条路会失效
        environment.getPropertySources().replace(sourceName, new MapPropertySource(sourceName, merged));

        List<String> rebound = rebind(plan.applied().keySet());
        log.info("Nacos 热更新生效 {} 键={} 重新绑定={}", dataId, plan.applied().keySet(), rebound);
        applicationContext.publishEvent(new NacosConfigChangedEvent(this, dataId, plan));
    }

    /** 重新绑定受影响的 {@code @ConfigurationProperties} 对象。 */
    private List<String> rebind(Iterable<String> changedKeys) {
        List<String> rebound = new ArrayList<>();
        Binder binder = Binder.get(environment);
        for (var entry : ConfigurationPropertiesBean.getAll(applicationContext).values()) {
            var annotation = entry.getAnnotation();
            if (annotation == null || annotation.prefix().isEmpty()) {
                continue;
            }
            String prefix = annotation.prefix();
            boolean affected = false;
            for (String key : changedKeys) {
                if (key.startsWith(prefix + ".")) {
                    affected = true;
                    break;
                }
            }
            if (!affected) {
                continue;
            }
            try {
                binder.bind(prefix, Bindable.ofInstance(entry.getInstance()));
                rebound.add(prefix);
            } catch (RuntimeException e) {
                // 构造器绑定（record 那种）改不了已存在的实例，绑定会失败。
                // 不吞掉：白名单里放了一个绑不上的键，等于承诺了一件做不到的事
                log.warn("配置 {} 无法就地重新绑定（多半是构造器绑定的不可变对象），该键需重启才生效：{}",
                        prefix, e.getMessage());
            }
        }
        return rebound;
    }

    /**
     * @return 解析失败时返回 {@code null}，与「解析出来是空的」区分开。
     *         两者混为一谈的后果是：一份语法坏掉的配置会被当成「所有键都被删了」，
     *         于是热更新把系统上所有白名单内的配置一次性清空
     */
    private Map<String, Object> parse(String sourceName, String dataId, String content) {
        if (content == null || content.isBlank()) {
            // Nacos 删除配置时推的就是空内容。这确实意味着「这份配置里的键都没了」
            return Map.of();
        }
        PropertySourceLoader loader = dataId.endsWith(".properties")
                ? new PropertiesPropertySourceLoader()
                : new YamlPropertySourceLoader();
        try {
            var loaded = loader.load(sourceName,
                    new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), sourceName));
            Map<String, Object> flat = new LinkedHashMap<>();
            for (PropertySource<?> source : loaded) {
                flat.putAll(snapshot(source));
            }
            return flat;
        } catch (Exception e) {
            // 推下来一份语法坏掉的配置。保持旧值不动是唯一安全的处置：
            // 部分应用会让系统进入一个既不是旧配置也不是新配置的状态
            log.error("Nacos 推送的 {} 解析失败，保持原配置不变：{}", dataId, e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> snapshot(PropertySource<?> source) {
        if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : enumerable.getPropertyNames()) {
            values.put(name, enumerable.getProperty(name));
        }
        return values;
    }

    private static String trimSuffix(String dataId) {
        int space = dataId.indexOf(' ');
        return space < 0 ? dataId : dataId.substring(0, space);
    }
}
