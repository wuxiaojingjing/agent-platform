package com.huawei.finance.contracts.port;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.ContextualQuery;
import com.huawei.finance.contracts.model.IntentContext;
import com.huawei.finance.stability.Spi;
import java.util.Map;

/**
 * 域内指代解析（架构草案 v0.3 §2.3、阶段 1.5）。
 *
 * <p>「用第二张卡转一半」里的「第二张卡」「一半」是**账户域的语义**：什么叫第二张、
 * 按哪个字段折半、别名怎么显示，都只有账户域知道。v0.3 §2.3 把这类解析明确列进
 * 入口的禁止清单——不是为了省事，是因为入口一旦自己解，26 个域的语义就会在手机银行助手里
 * 各抄一份，然后跟着域各自演进，直到两边对不上。
 *
 * <p>所以入口只认这个端口，不认任何具体域：它把槽位、租约、本轮文本交出去，
 * 拿回补全后的槽位，中间是谁解的、按什么规则解的，入口都不知道。
 *
 * <p>实现方按 {@link #techDomainCode()} 声明自己解哪个域。入口不做域选择——
 * 域路由的结果是委托给谁，而不是「入口先挑一个解析器再自己解」。
 *
 * <p>自然语言指代由上游模型输出结构化语义槽位；本端口不读取句式、不穷举表达。
 * 它只负责用领域权威事实验证并映射这些槽位；无法验证时必须保留未解状态，不得猜测。
 */
@Spi
public interface DomainReferenceResolver {

    /** 本解析器负责的科技域码，取自 {@code assets/domains/tech-domains.yaml}。 */
    String techDomainCode();

    /**
     * 把槽位里的域内指代标记解成具体值。
     *
     * @param slots 快路径已抽槽位，可能含域内标记
     * @param lease 本轮上下文租约，跨 Agent 事实从这里取
     * @param query 本轮文本，标记缺失时的兜底来源
     * @return 补全后的**新** Map；解不出一律保留原样，不得抹掉已有槽位
     */
    Map<String, Object> resolve(Map<String, Object> slots, ContextLease lease, String query);

    /**
     * Rich request used by remote resolvers. Existing domain-local resolvers keep the narrow method;
     * the default preserves source and binary behavior while allowing the platform to provide only
     * policy-validated context references and the selected capability.
     */
    default Map<String, Object> resolve(Map<String, Object> slots, ContextLease lease, String query,
                                        IntentContext intentContext, ContextualQuery contextualQuery,
                                        CapabilityCard selectedCapability) {
        return resolve(slots, lease, query);
    }
}
