package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Api;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.huawei.finance.contracts.model.ContextDelta;

/**
 * 委托回执（架构草案 v0.2 §6.1、§7 第 3–4 条）。
 *
 * <p><b>GOAL 的回执一律走强制信封。</b>缺信封或版本不符即整单 {@link DelegationOutcome#FATAL}，
 * 宁可拒绝也不猜。这道校验防的是一个具体事故:下游若错配到通用对话 Handler，
 * 模型会回一句「已为您转账 1000 元」,而中控会把一笔从未发生的转账记为成功——
 * 没有异常、没有告警，账实不符要等对账才发现。
 *
 * <p>{@code facts} 是**受控结构化字段**，不是叙述。下游可以自由地决定怎么办这件事，
 * 但回给上游的「办成了什么」必须结构化。{@code diagnostics} 里的自由文本只能用于排障,
 * 不得进入上游事实集、不得进入入口生成 prompt（§8.6）。
 *
 * <p>{@code missingSlots} 承载 {@code NEED_USER} 的结构化缺失信息（槽位名、候选、原因码），
 * 不回面客自然语言——面客话术只由入口生成（§7 第 1、4 条）。
 */
@Api
public record DelegationReceipt(
        String version,
        String delegationId,
        DelegationOutcome outcome,
        Map<String, Object> facts,
        List<MissingSlot> missingSlots,
        String reasonCode,
        String diagnostics,
        ContextDelta contextDelta) {

    public DelegationReceipt {
        Objects.requireNonNull(delegationId, "回执必须带 delegationId");
        Objects.requireNonNull(outcome, "回执必须带 outcome");
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    public DelegationReceipt(String version, String delegationId, DelegationOutcome outcome,
                             Map<String, Object> facts, List<MissingSlot> missingSlots,
                             String reasonCode, String diagnostics) {
        this(version, delegationId, outcome, facts, missingSlots, reasonCode, diagnostics, null);
    }

    /**
     * 结构化缺槽。
     *
     * @param slot 槽位名
     * @param options 候选值；空表示自由填写
     * @param reasonCode 为什么缺，供入口归因，不直接面客
     */
    public record MissingSlot(String slot, List<String> options, String reasonCode) {
        public MissingSlot {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * 强制信封校验。
     *
     * <p>校验的是「这是不是一份合规回执」，而不是「业务成不成」:一个只会说自然语言的
     * Handler 交不出版本正确的信封，因此它编的成功在这里就被拦住,而不是等到对账。
     *
     * @param raw 下游返回物，可能为 null（超时、连不上、根本没实现信封）
     * @param expectedDelegationId 本次委托的 id，回执必须对得上
     * @return 原回执（校验通过），或一份 FATAL 回执
     */
    public static DelegationReceipt requireValidEnvelope(DelegationReceipt raw,
                                                        String expectedDelegationId) {
        if (raw == null) {
            return fatal(expectedDelegationId, "A2A_RECEIPT_MISSING",
                    "下游未返回信封:可能是超时、不可达，或对方根本没实现回执契约");
        }
        if (!DelegationEnvelope.CURRENT_VERSION.equals(raw.version())) {
            return fatal(expectedDelegationId, "A2A_RECEIPT_VERSION_MISMATCH",
                    "回执版本不符 期望=" + DelegationEnvelope.CURRENT_VERSION + " 实际=" + raw.version());
        }
        if (!expectedDelegationId.equals(raw.delegationId())) {
            return fatal(expectedDelegationId, "A2A_RECEIPT_DELEGATION_MISMATCH",
                    "回执 delegationId 对不上 期望=" + expectedDelegationId + " 实际=" + raw.delegationId());
        }
        // 声称办成了却一个结构化事实都没有:这正是「模型编了一句成功」的形状。
        // 自由文本再像样也不算事实（§8.6）
        if (raw.outcome() == DelegationOutcome.SUCCEEDED && raw.facts().isEmpty()) {
            return fatal(expectedDelegationId, "A2A_RECEIPT_FACTS_EMPTY",
                    "回执声称 SUCCEEDED 但未给任何结构化事实");
        }
        return raw;
    }

    public static DelegationReceipt fatal(String delegationId, String reasonCode, String diagnostics) {
        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, delegationId,
                DelegationOutcome.FATAL, Map.of(), List.of(), reasonCode, diagnostics, null);
    }

    public static DelegationReceipt succeeded(String delegationId, Map<String, Object> facts) {
        return new DelegationReceipt(DelegationEnvelope.CURRENT_VERSION, delegationId,
                DelegationOutcome.SUCCEEDED, facts, List.of(), null, null, null);
    }
}
