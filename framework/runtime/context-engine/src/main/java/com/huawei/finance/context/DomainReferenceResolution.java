package com.huawei.finance.context;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.contracts.port.DomainReferenceResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 域内指代解析的入口侧组合（阶段 1.5）。
 *
 * <p>入口只认这一个类：它依次问过所有已注册的域解析器，每个解析器只改自己认识的标记，
 * 不改别人的。入口不知道「哪个解析器对哪个域负责」——这是刻意的，
 * 因为那个知识一旦进了入口，§2.3 的禁令就只剩注释效力了。
 *
 * <p>没有域解析器时（比如测试环境只加载了引擎而没加载域模块），槽位原样透传，
 * 不抛异常——调用方拿到的是未解析的域内标记本身，不是 NPE。标记长什么样是域侧的事，
 * 这里刻意不举例:连注释里的字面量都算入口知识，{@code ThinEntryBoundaryTest} 会拦。
 *
 * <p>为什么不按域码路由后再调：入口在这里看不到域路由结论，它只有槽位和文本。
 * 多个解析器都说「我不认识这些标记」是正常的，幂等、无副作用。
 */
public class DomainReferenceResolution {

    private final List<DomainReferenceResolver> resolvers;

    public DomainReferenceResolution(List<DomainReferenceResolver> resolvers) {
        this.resolvers = resolvers == null ? List.of() : List.copyOf(resolvers);
    }

    /**
     * 链式调用所有解析器，前一个的输出是后一个的输入。
     *
     * @param slots 快路径已抽槽位，可能含域内标记
     * @param lease 本轮上下文租约
     * @param query 本轮文本
     * @return 补全后的新 Map；解不出一律保留原样
     */
    public Map<String, Object> enrich(Map<String, Object> slots,
                                      ContextLease lease, String query) {
        return enrich(slots, lease, query, null, null, null);
    }

    public Map<String, Object> enrich(Map<String, Object> slots, ContextLease lease, String query,
                                      IntentContext intentContext, ContextualQuery contextualQuery,
                                      CapabilityCard selectedCapability) {
        Map<String, Object> current = new LinkedHashMap<>(slots == null ? Map.of() : slots);
        for (DomainReferenceResolver resolver : resolvers) {
            current = resolver.resolve(current, lease, query, intentContext, contextualQuery,
                    selectedCapability);
        }
        return current;
    }
}
