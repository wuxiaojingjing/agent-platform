package com.huawei.finance.nacos.config;

import com.huawei.finance.nacos.NacosProperties;
import java.util.List;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Bindable;

/**
 * 解析 {@code spring.config.import: optional:nacos:mobile-banking-assistant.yaml}。
 *
 * <h2>为什么走 ConfigData 而不是 EnvironmentPostProcessor</h2>
 *
 * <p>两者都能把远端配置塞进 Environment，区别在**顺序是不是可说明的**。
 * {@code ConfigData} 有 Boot 定义好的优先级：import 进来的配置压过 application.yml，
 * 但压不过命令行参数与环境变量。这条顺序很重要——出事时运维要能用环境变量当场压掉
 * 一个从配置中心推下来的坏值，而不是先去配置中心改。
 * {@code EnvironmentPostProcessor} 得自己决定插在 property source 链的哪一节，
 * 而那个决定不会写在任何人找得到的地方。
 *
 * <h2>为什么不用 Spring Cloud Alibaba</h2>
 *
 * <p>它最新的 2025.1.0.0 对着 Spring Boot 3.5，本工程锁在 4.0.6（跟随 agent-runtime-java
 * 的 parent）。为一个配置中心降主框架版本不划算，而这层桥接总共两个类。
 */
public class NacosConfigDataLocationResolver
        implements ConfigDataLocationResolver<NacosConfigDataResource> {

    static final String PREFIX = "nacos:";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<NacosConfigDataResource> resolve(
            ConfigDataLocationResolverContext context, ConfigDataLocation location) {

        NacosProperties properties = context.getBinder()
                .bind("huawei.finance.agent.nacos", Bindable.of(NacosProperties.class))
                .orElseGet(NacosProperties::new);

        // 连接参数在这个阶段只能来自本地配置与环境变量，这是刻意的：
        // 「去哪里取配置」这件事自己不能是取回来的配置
        context.getBootstrapContext()
                .registerIfAbsent(NacosConfigServiceFactory.class,
                        ignored -> new NacosConfigServiceFactory(properties));

        String spec = location.getNonPrefixedValue(PREFIX);
        String group = properties.getConfig().getGroup();
        String dataId = spec;
        // 允许 nacos:GROUP/dataId 就地覆盖分组，省得为一个跨组的配置去改全局分组
        int slash = spec.indexOf('/');
        if (slash > 0) {
            group = spec.substring(0, slash);
            dataId = spec.substring(slash + 1);
        }
        if (dataId.isBlank()) {
            throw new IllegalArgumentException(
                    "nacos: 后面要给 dataId，例如 nacos:mobile-banking-assistant.yaml，实际是 " + location);
        }
        return List.of(new NacosConfigDataResource(dataId, group));
    }
}
