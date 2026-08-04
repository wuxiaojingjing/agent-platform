package com.huawei.finance.nacos.config;

import com.alibaba.nacos.api.exception.NacosException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/** 把 Nacos 上的一个 dataId 读成 Spring 的 property source。 */
public class NacosConfigDataLoader implements ConfigDataLoader<NacosConfigDataResource> {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigDataLoader.class);

    static final String SINGLETON_NAME = "agentNacosConfigServiceFactory";

    @Override
    public ConfigData load(ConfigDataLoaderContext context, NacosConfigDataResource resource)
            throws IOException, ConfigDataResourceNotFoundException {

        NacosConfigServiceFactory factory =
                context.getBootstrapContext().get(NacosConfigServiceFactory.class);
        promoteToApplicationContext(context, factory);

        String content;
        try {
            content = factory.get().getConfig(
                    resource.dataId(),
                    resource.group(),
                    factory.properties().getConfig().getTimeoutMs());
        } catch (NacosException e) {
            // 连不上、超时、鉴权失败都在这里。翻成「资源不存在」，让 optional: 前缀能生效：
            // 配置中心不可用时服务应当用本地默认值起来，而不是起不来。
            // 想要「连不上就不许启动」的环境，把 optional: 去掉即可，Boot 会照抛
            log.warn("从 Nacos 取 {} 失败，按缺失处理：{}", resource, e.getMessage());
            throw new ConfigDataResourceNotFoundException(resource, e);
        }
        if (content == null || content.isBlank()) {
            throw new ConfigDataResourceNotFoundException(resource);
        }

        List<PropertySource<?>> sources = loaderFor(resource.dataId()).load(
                "nacos:" + resource.group() + "/" + resource.dataId(),
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8), resource.toString()));
        log.info("已从 Nacos 载入配置 {}", resource);
        return new ConfigData(sources);
    }

    /**
     * 把启动期建好的客户端交给容器。
     *
     * <p>不这么做的话热更新监听器只能自己再建一个 {@code ConfigService}，于是一个进程
     * 两条长连接、两份本地快照缓存，而两者的内容可以不一致。
     */
    private void promoteToApplicationContext(
            ConfigDataLoaderContext context, NacosConfigServiceFactory factory) {
        context.getBootstrapContext().addCloseListener(event -> {
            var beanFactory = event.getApplicationContext().getBeanFactory();
            if (!beanFactory.containsSingleton(SINGLETON_NAME)) {
                beanFactory.registerSingleton(SINGLETON_NAME, factory);
            }
        });
    }

    private static PropertySourceLoader loaderFor(String dataId) {
        if (dataId.endsWith(".properties")) {
            return new PropertiesPropertySourceLoader();
        }
        if (dataId.endsWith(".yaml") || dataId.endsWith(".yml")) {
            return new YamlPropertySourceLoader();
        }
        // 不猜格式。Nacos 控制台上 dataId 带不带后缀是自由的，而「按内容猜是 yaml 还是
        // properties」猜错时不会报错，只会安静地少几个键
        throw new IllegalArgumentException(
                "dataId " + dataId + " 需以 .yaml / .yml / .properties 结尾，以便确定格式");
    }
}
