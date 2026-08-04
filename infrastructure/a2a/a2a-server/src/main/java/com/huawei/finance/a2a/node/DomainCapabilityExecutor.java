package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.stability.Spi;
import java.util.List;
import java.util.Map;

/**
 * 域内执行:一个科技域接下委托之后真正做的事（架构草案 v0.3 §5.2）。
 *
 * <p>这是 26 个域各自要实现的那一小块。域外的部分——判 NOT_MINE 的框架、组回执、
 * 拦「声称成功但没事实」——由 {@link DomainAgentNode} 统一处理。
 *
 * <p>域侧**必须**自己保证:{@code facts} 是受控结构化字段，不是一段叙述。
 * 自由文本只能进 {@code diagnostics}，那里的内容不进上游事实集、
 * 不进入口生成 prompt（§8.6）。
 */
@Spi
public interface DomainCapabilityExecutor {

    /**
     * GOAL 模式下:本域认不认这件事。
     *
     * <p>由域侧判而不是入口判，正是 GOAL 的价值所在——下游领域方比上游更懂自己那摊事。
     * 认不下就回 false，节点会回 {@code NOT_MINE} 让入口改投一次，
     * 而不是勉强办一个自己也不确定的请求。
     */
    boolean claims(DelegationEnvelope envelope);

    /**
     * 归属判定，TASK 与 GOAL 都问这里。
     *
     * <p>返回 {@code empty()} 表示「我不表态」，节点会退回按能力 ID 前缀兜底。
     * 域侧能准确回答时应当回答:前缀不是路由真值，资产里就有反例——
     * {@code cap.transfer} 属于转账域但没有域名段，{@code cap.card.replace} 同时被账户域
     * 与信用卡域承接。这两条按前缀会被所有域判成「不是我的」,
     * 于是一次完全正确的派单被回成 NOT_MINE，改投之后仍然无人接。
     *
     * <p>默认不表态，好让只关心 GOAL 的域侧实现不必动这个方法。
     */
    default java.util.Optional<Boolean> owns(DelegationEnvelope envelope) {
        return java.util.Optional.empty();
    }

    /** 执行。不得抛异常穿透——异常穿透会让委托既没有回执也没有终态。 */
    Outcome execute(DelegationEnvelope envelope);

    /**
     * 域内执行结局。
     *
     * @param outcome 结局；{@code SUCCEEDED} 时 {@code facts} 不得为空
     * @param facts 受控结构化事实
     * @param missingSlots {@code NEED_USER} 时的结构化缺槽，不回面客话术
     * @param reasonCode 归因用原因码
     * @param diagnostics 自由文本，仅供排障
     */
    record Outcome(DelegationOutcome outcome,
                   Map<String, Object> facts,
                   List<DelegationReceipt.MissingSlot> missingSlots,
                   String reasonCode,
                   String diagnostics) {

        public Outcome {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
            missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        }

        public static Outcome succeeded(Map<String, Object> facts) {
            return new Outcome(DelegationOutcome.SUCCEEDED, facts, List.of(), null, null);
        }

        public static Outcome notMine(String why) {
            return new Outcome(DelegationOutcome.NOT_MINE, Map.of(), List.of(), "NOT_MINE", why);
        }

        public static Outcome needUser(List<DelegationReceipt.MissingSlot> missing) {
            return new Outcome(DelegationOutcome.NEED_USER, Map.of(), missing,
                    "NEED_USER", null);
        }

        /** 本域尚未建成。不得假成功、不得回假数据（v0.3 §5.4）。 */
        public static Outcome notOpen(String why) {
            return new Outcome(DelegationOutcome.DOMAIN_NOT_OPEN, Map.of(), List.of(),
                    "DOMAIN_NOT_OPEN", why);
        }
    }
}
