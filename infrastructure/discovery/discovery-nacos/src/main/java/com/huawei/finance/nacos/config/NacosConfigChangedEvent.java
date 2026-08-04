package com.huawei.finance.nacos.config;

import org.springframework.context.ApplicationEvent;

/**
 * 一次生效了的热更新。
 *
 * <p>只在**确有键被应用**时发布，不为「推了但都不在白名单」发事件——那种情况下系统状态
 * 没有任何变化，发事件会让监听方误以为要重算什么。
 *
 * <p>给需要主动重算的组件用（比如缓存要作废、连接池要重建）。绝大多数消费方不需要监听：
 * {@code @ConfigurationProperties} 对象已经被重新绑定过了。
 */
public class NacosConfigChangedEvent extends ApplicationEvent {

    private final String dataId;
    private final HotReloadPolicy.Plan plan;

    public NacosConfigChangedEvent(Object source, String dataId, HotReloadPolicy.Plan plan) {
        super(source);
        this.dataId = dataId;
        this.plan = plan;
    }

    public String dataId() {
        return dataId;
    }

    public HotReloadPolicy.Plan plan() {
        return plan;
    }
}
