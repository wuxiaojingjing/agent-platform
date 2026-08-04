package com.huawei.finance.nacos.discovery;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.List;

/**
 * 只把注册中心当成「列服务、列实例」两个动作看。
 *
 * <p>{@link NamingService} 有二十多个方法，用例里没法做替身。这层接口存在的唯一理由
 * 就是让服务发现的判定逻辑（选哪个实例、能力怎么匹配、下线了怎么办）能脱离真 Nacos 测。
 */
public interface NamingGateway {

    /** Nacos 对分页参数有上界校验，取一个稳妥的值。 */
    int PAGE_SIZE = 200;

    /** 该组下已注册的服务名。 */
    List<String> services() throws NacosException;

    /** 某个服务的全部实例，含不健康的。 */
    List<Instance> instances(String serviceName) throws NacosException;

    /** 基于真 Nacos 客户端的实现。 */
    static NamingGateway of(NamingService naming, String group) {
        return new NamingGateway() {
            @Override
            public List<String> services() throws NacosException {
                // 老老实实翻页。曾经图省事传 pageSize=Integer.MAX_VALUE，
                // Nacos 2.x 的参数校验（paramCheckEnabled 默认开）会把这种请求判为非法，
                // 而它的表现是**返回空列表**而不是报错——看起来就像一个服务都没注册
                List<String> all = new java.util.ArrayList<>();
                int page = 1;
                while (true) {
                    List<String> chunk = naming.getServicesOfServer(page, PAGE_SIZE, group).getData();
                    if (chunk == null || chunk.isEmpty()) {
                        return all;
                    }
                    all.addAll(chunk);
                    if (chunk.size() < PAGE_SIZE) {
                        return all;
                    }
                    page++;
                }
            }

            @Override
            public List<Instance> instances(String serviceName) throws NacosException {
                // 用 getAllInstances 而不是 selectInstances(..., false)：后者那个布尔是
                // **筛选值**不是开关，传 false 得到的是「只要不健康的那些」，
                // 于是一切正常时反而返回空表。健康状态由调用方从实例上读，
                // 这里一个都不能少——「没有这个 Agent」和「它挂了」必须分得清
                return naming.getAllInstances(serviceName, group);
            }
        };
    }
}
