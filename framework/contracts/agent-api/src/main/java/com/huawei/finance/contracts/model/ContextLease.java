package com.huawei.finance.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.huawei.finance.stability.Api;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 本轮最小工作集（v0.7 §3.1、§4.0，FP-28）。
 *
 * <p>入口不拼接完整历史，而是按预算申请一份租约。这不是省钱的优化，是面客的硬约束：
 * 历史越长模型越容易被前几轮的旧事实带偏，而银行场景里「用旧金额执行新指令」是事故不是瑕疵。
 *
 * <p><b>为什么带 {@code trustworthy} 而不是让调用方自己判断</b>：
 * v0.7 §4.0 要求上下文服务异常时「有副作用停止，只读按策略降级」。
 * 如果把这个判断留给调用方，它就会以「上下文取不到，那就当空的继续办」的形式被绕过——
 * 空上下文与可信的空上下文是两回事，前者意味着我们不知道用户刚才确认过什么。
 * 因此租约自己回答 {@link #allowsSideEffects()}，调用方无从自行解释。
 *
 * @param goal 本轮目标，通常是活跃任务的原句
 * @param confirmedFacts 已确认事实，即用户明确给过或确认过的槽位
 * @param pendingItems 待澄清项
 * @param toolConclusions 历史轮的工具结论，受控枚举编码（不得为自然语言）
 * @param budgetTokens 预算上限
 * @param usedTokens 编译后实际占用
 * @param trimmed 因超预算被裁掉的条目，要求可见（FP-28 验收条件）
 * @param trustworthy 上下文是否完整可信；为假时禁止一切有副作用的执行
 * @param stateVersion 任务态版本，用于检测租约签发后状态是否已变
 * @param expiresAt 租约有效期
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Api
public record ContextLease(
        String leaseId,
        String sessionId,
        String goal,
        Map<String, Object> confirmedFacts,
        List<PendingItem> pendingItems,
        List<ToolConclusion> toolConclusions,
        int budgetTokens,
        int usedTokens,
        List<TrimmedItem> trimmed,
        boolean trustworthy,
        long stateVersion,
        Instant expiresAt) {

    public ContextLease {
        confirmedFacts = confirmedFacts == null ? Map.of() : Map.copyOf(confirmedFacts);
        pendingItems = pendingItems == null ? List.of() : List.copyOf(pendingItems);
        toolConclusions = toolConclusions == null ? List.of() : List.copyOf(toolConclusions);
        trimmed = trimmed == null ? List.of() : List.copyOf(trimmed);

        if (usedTokens > budgetTokens) {
            throw new IllegalArgumentException(
                    "编译后仍超预算（" + usedTokens + " > " + budgetTokens
                            + "），说明裁剪没有真正执行。租约不得在超预算状态下签发");
        }
    }

    /**
     * 一份不可信的空租约。上下文服务异常时签发。
     *
     * <p>刻意保留 {@code sessionId} 与目标：面客话术仍要能说清「您刚才说的这件事」，
     * 只是不允许据此动账。
     */
    public static ContextLease degraded(String sessionId, String goal, Instant expiresAt) {
        return new ContextLease(null, sessionId, goal, Map.of(), List.of(), List.of(),
                0, 0, List.of(), false, -1L, expiresAt);
    }

    /**
     * 本租约是否允许执行有副作用的操作。
     *
     * <p>过期与不可信都判否。过期的租约意味着任务态可能已被另一路请求改写，
     * 拿着它去动账等于用一份读到一半的状态做决定。
     */
    public boolean allowsSideEffects() {
        return trustworthy && !isExpired(Instant.now());
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** 是否发生过裁剪。用于打点与话术提示，不影响可信性——裁剪是预期行为，不是异常。 */
    public boolean wasTrimmed() {
        return !trimmed.isEmpty();
    }

    /**
     * 待澄清项。
     *
     * @param slot 槽位名
     * @param action 等用户做什么
     * @param options 供选择的候选项，仅 {@link Enums.PendingAction#SELECT} 时有值
     */
    public record PendingItem(String slot, Enums.PendingAction action, List<String> options) {
        public PendingItem {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * 历史轮的工具结论。
     *
     * <p>没有自然语言字段是刻意的（FP-28）。要描述「上一步办到哪儿了」，
     * 这三个受控字段就够；给一个自由文本字段，它一定会被填成一段解释。
     *
     * @param capabilityId 哪个能力
     * @param outcome 结局
     * @param pending 结束时挂在用户身上的待办
     * @param facts 该次执行产出的、可被后续轮引用的事实
     */
    public record ToolConclusion(
            String capabilityId,
            Enums.ToolOutcome outcome,
            Enums.PendingAction pending,
            Map<String, Object> facts) {
        public ToolConclusion {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    /**
     * 被裁掉的条目。
     *
     * <p>只留引用与理由，不留内容——留了内容就等于没裁。
     *
     * @param ref 被裁条目的引用，可据此回查原始记录
     * @param reason 裁剪理由
     * @param tokens 裁掉省下多少
     */
    public record TrimmedItem(String ref, String reason, int tokens) {
    }
}
