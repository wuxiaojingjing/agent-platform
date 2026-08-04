package com.huawei.finance.nacos.config;

import java.util.Objects;
import org.springframework.boot.context.config.ConfigDataResource;

/**
 * 一个 {@code spring.config.import: nacos:xxx.yaml} 指向的配置。
 *
 * <p>{@code optional} 由 Boot 的 {@code optional:} 前缀决定，这里不重复表达。
 */
public class NacosConfigDataResource extends ConfigDataResource {

    private final String dataId;
    private final String group;

    public NacosConfigDataResource(String dataId, String group) {
        this.dataId = dataId;
        this.group = group;
    }

    public String dataId() {
        return dataId;
    }

    public String group() {
        return group;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NacosConfigDataResource other)) {
            return false;
        }
        return dataId.equals(other.dataId) && group.equals(other.group);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataId, group);
    }

    @Override
    public String toString() {
        return "nacos:" + group + "/" + dataId;
    }
}
