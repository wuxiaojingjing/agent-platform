package com.huawei.finance.domain.account;

import com.huawei.finance.contracts.model.ContextLease;
import com.huawei.finance.contracts.model.SlotNames;
import com.huawei.finance.contracts.port.DomainReferenceResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户域的域内指代解析（原 {@code com.huawei.finance.context.WorkingMemoryResolver}）。
 *
 * <p>这段逻辑此前住在 {@code context-engine}，也就是入口层：入口自己认识「第几张卡」、
 * 自己知道按 {@code availableBalance} 折半、自己拿 {@code alias} 当展示名。
 * 架构草案 v0.3 §2.3 把这类解析列入入口禁止清单，阶段 1.5 要求下沉到域侧，
 * 入口只经 {@link DomainReferenceResolver} 端口拿结果。
 *
 * <p>模型只把自然语言解析为 {@code accountOrdinal} 和 {@code amountBasis}。
 * 本类不再读取原句或穷举表达，只使用租约中的有序账户事实完成确定性引用映射，
 * 并把余额比例转成执行前必须求值的内部依据。会话事实可以帮助解析账户和计算对象，
 * 但本类不会把余额快照计算成具体执行金额。
 */
public class AccountReferenceResolver implements DomainReferenceResolver {

    /** 科技域码，与 {@code assets/domains/tech-domains.yaml} 对齐。 */
    public static final String TECH_DOMAIN_CODE = "account";

    public static final String AMOUNT_BASIS_SLOT = "__context.amountBasis";
    public static final String ACCOUNT_ORDINAL_SLOT = "__context.accountOrdinal";
    public static final String REQUERY_THEN_HALF = "REQUERY_THEN_HALF";

    @Override
    public String techDomainCode() {
        return TECH_DOMAIN_CODE;
    }

    /**
     * @param slots 模型和快路径产出的结构化槽位
     * @param lease 本轮租约
     * @param query 原始文本；本实现不读取，仅为 SPI 兼容保留
     * @return 新 Map，能验证的序号与余额依据被映射为会话级结构化上下文
     */
    @Override
    public Map<String, Object> resolve(Map<String, Object> slots, ContextLease lease, String query) {
        Map<String, Object> out = new LinkedHashMap<>(slots == null ? Map.of() : slots);

        Integer ordinal = ordinalOf(out.get(SlotNames.ACCOUNT_ORDINAL));
        boolean half = REQUERY_THEN_HALF.equals(out.get(SlotNames.AMOUNT_BASIS));

        if (ordinal == null && !half) {
            return out;
        }

        List<Map<String, Object>> cards = latestCards(lease);
        if (cards.isEmpty()) {
            return out;
        }

        Map<String, Object> card = null;
        if (ordinal != null) {
            card = cardAt(cards, ordinal);
            if (card != null) {
                Object alias = card.get("alias");
                if (alias != null && !String.valueOf(alias).isBlank()) {
                    out.put(SlotNames.FROM_ACCOUNT, String.valueOf(alias));
                }
            }
        }

        if (half) {
            if (card == null) {
                card = cardAt(cards, ordinal != null ? ordinal : 1);
            }
            if (card != null) {
                out.put(AMOUNT_BASIS_SLOT, REQUERY_THEN_HALF);
                out.put(ACCOUNT_ORDINAL_SLOT, ordinal != null ? ordinal : 1);
            }
        }

        return out;
    }

    private static Integer ordinalOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException invalid) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> latestCards(ContextLease lease) {
        if (lease == null || lease.toolConclusions() == null) {
            return List.of();
        }
        for (int i = lease.toolConclusions().size() - 1; i >= 0; i--) {
            ContextLease.ToolConclusion conclusion = lease.toolConclusions().get(i);
            Object cards = conclusion.facts().get("cards");
            if (cards instanceof List<?> list && !list.isEmpty()) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(m -> (Map<String, Object>) m)
                        .toList();
            }
        }
        return List.of();
    }

    private static Map<String, Object> cardAt(List<Map<String, Object>> cards, int ordinal) {
        if (ordinal < 1 || ordinal > cards.size()) {
            return null;
        }
        for (Map<String, Object> card : cards) {
            Object index = card.get("index");
            if (index instanceof Number n && n.intValue() == ordinal) {
                return card;
            }
            if (index != null && String.valueOf(index).equals(String.valueOf(ordinal))) {
                return card;
            }
        }
        return cards.get(ordinal - 1);
    }

}
