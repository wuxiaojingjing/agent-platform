package com.huawei.finance.nacos.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.huawei.finance.nacos.NacosProperties;

/**
 * 持有唯一的 {@link ConfigService}。
 *
 * <p>建在启动的 {@code ConfigData} 阶段（那时还没有容器），随后原样交给容器供热更新监听器复用。
 * 一个进程只建一个：每个 {@code ConfigService} 都自带长连接与心跳线程，
 * 按 dataId 各建一个会让连接数随配置文件数量增长，而那是没人会注意到的泄漏。
 */
public class NacosConfigServiceFactory {

    private final NacosProperties properties;

    private volatile ConfigService configService;

    public NacosConfigServiceFactory(NacosProperties properties) {
        this.properties = properties;
    }

    public NacosProperties properties() {
        return properties;
    }

    public ConfigService get() throws NacosException {
        ConfigService local = configService;
        if (local == null) {
            synchronized (this) {
                local = configService;
                if (local == null) {
                    local = NacosFactory.createConfigService(properties.toClientProperties());
                    configService = local;
                }
            }
        }
        return local;
    }

    /** 已经建过没有，供装配期判断要不要复用。 */
    public boolean isInitialized() {
        return configService != null;
    }
}
