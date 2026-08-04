package com.huawei.finance.nacos.discovery;

import com.huawei.finance.contracts.a2a.AgentEndpointResolver;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 先问注册中心，问不到再看配置里的静态路由表。
 *
 * <h2>为什么保留静态兜底</h2>
 *
 * <p>迁移期一定是混合的：一部分领域进程已经注册，一部分还没有。若发现不到就直接判无人承接，
 * 接入注册中心的那一刻所有没来得及改的领域全部停摆。反过来，注册中心上有的一律优先，
 * 因此一个能力一旦注册上来，静态表里那条过期的地址就自动失效——不需要有人记得去删。
 *
 * <p>这个顺序也意味着：静态表里配了地址、注册中心上又没有健康实例时，请求仍会打到静态地址上。
 * 想要「下线即停派」的语义，就得把静态表清空，这一点在日志里说明白。
 */
public class NacosEndpointResolver implements AgentEndpointResolver {

    private static final Logger log = LoggerFactory.getLogger(NacosEndpointResolver.class);

    private final NacosAgentDirectory directory;
    private final AgentEndpointResolver fallback;

    public NacosEndpointResolver(NacosAgentDirectory directory, AgentEndpointResolver fallback) {
        this.directory = directory;
        this.fallback = fallback;
        if (!fallback.knownCapabilities().isEmpty()) {
            log.info("静态路由表仍在生效，作为注册中心的兜底：{}。"
                    + "要让「实例下线即停止派单」成立，需清空 huawei.finance.sample.openjiuwen.endpoints",
                    fallback.knownCapabilities());
        }
    }

    @Override
    public Optional<String> resolve(String capabilityId) {
        Optional<String> discovered = directory.resolve(capabilityId);
        if (discovered.isPresent()) {
            return discovered;
        }
        return fallback.resolve(capabilityId);
    }

    @Override
    public Set<String> knownCapabilities() {
        Set<String> all = new TreeSet<>(directory.knownCapabilities());
        all.addAll(fallback.knownCapabilities());
        return all;
    }

    @Override
    public String source() {
        return "nacos+static";
    }
}
