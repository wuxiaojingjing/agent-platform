package com.huawei.finance.a2a.node;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationOutcome;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把已有的 {@link TechDomainAgent} 接成 A2A 的域内执行器（架构草案 v0.3 阶段 3b）。
 *
 * <p>这一层的存在理由是**不要求 26 个域重写一遍业务**。域里已经有能跑的
 * {@code TechDomainAgent} 了，阶段 3b 要的是「Card 可发现 + 至少一条主路径 + 未开放能力
 * 显式失败」，不是把业务逻辑搬个家。让每个域各写一份 A2A 适配，等于给同一份业务开两个入口，
 * 而两个入口的行为迟早会分叉——分叉的那天，走 A2A 的用户和走中控的用户看到的是两个答案。
 *
 * <p><b>能力边界按 {@link TechDomainAgent#supports} 判，不按域码前缀。</b>
 * {@code cap.card.replace} 同时被账户域和信用卡域承接（换卡这件事两边都办），
 * 按前缀判会把它判成两个域都不认。路由真值是 {@code supports}，这一点与中控侧一致。
 */
public class DomainAgentExecutor implements DomainCapabilityExecutor {

    private static final Logger log = LoggerFactory.getLogger(DomainAgentExecutor.class);

    private final TechDomainAgent agent;
    private final GoalCapabilityResolver goalResolver;

    public DomainAgentExecutor(TechDomainAgent agent, GoalCapabilityResolver goalResolver) {
        this.agent = agent;
        this.goalResolver = goalResolver;
    }

    /**
     * GOAL 模式:本域能不能把这句话落到一个自己承接的能力上。
     *
     * <p>落不到就不认领。勉强认领再回失败，和回 {@code NOT_MINE} 对用户是两种结果:
     * 后者入口还会改投一次，前者直接就是一次失败。
     */
    @Override
    public boolean claims(DelegationEnvelope envelope) {
        return goalResolver.resolve(envelope.goal(), agent).isPresent();
    }

    /**
     * 归属:TASK 看 {@code supports}，GOAL 看解析得出不出来。
     *
     * <p>这里明确表态而不留给前缀兜底,因为 {@code supports} 就是路由真值。
     *
     * <p><b>能力未开放时仍然表态「属于本域」。</b>这不是笔误:域码对得上而实现没开放，
     * 该回 DOMAIN_NOT_OPEN（交付进度）而不是 NOT_MINE（域路由判错）。
     * 在归属这一步就否掉的话，两种截然不同的归因会被压成同一个结局，
     * 入口判错会被永远计成「域没做完」。所以未开放的判定留到 execute 里做。
     */
    @Override
    public java.util.Optional<Boolean> owns(DelegationEnvelope envelope) {
        if (envelope.mode() == com.huawei.finance.contracts.a2a.DelegationMode.TASK) {
            String capabilityId = envelope.capabilityId();
            if (capabilityId == null) {
                return java.util.Optional.of(false);
            }
            // supports 为真 → 确属本域；为假 → 可能是「本域的能力但没开放」，
            // 交给域码前缀再看一眼，前缀也不认才真的不是本域的事
            return agent.supports(capabilityId)
                    ? java.util.Optional.of(true)
                    : java.util.Optional.empty();
        }
        return java.util.Optional.of(claims(envelope));
    }

    @Override
    public Outcome execute(DelegationEnvelope envelope) {
        String capabilityId = envelope.capabilityId();
        if (capabilityId == null) {
            capabilityId = goalResolver.resolve(envelope.goal(), agent).orElse(null);
            if (capabilityId == null) {
                return Outcome.notMine("本域无法把该目标落到承接的能力上");
            }
        }

        // 未开放能力显式失败（阶段 3b 门禁第三条）。这里不是「不属于本域」——
        // 域码对得上而能力没开放，是交付进度；回 NOT_MINE 会让入口白改投一次
        if (!agent.supports(capabilityId)) {
            log.info("能力未在本域开放 domain={} capability={}",
                    agent.techDomainCode(), capabilityId);
            return Outcome.notOpen("能力未在本域开放 capability=" + capabilityId);
        }

        TaskResult result;
        try {
            result = agent.execute(taskFor(envelope, capabilityId));
        } catch (RuntimeException e) {
            // 异常不得穿透:穿透会让这次委托既没有回执也没有终态，
            // 上游只能等到 deadline，而那时它无从判断钱动了没有
            log.error("域内执行抛异常 delegation={} capability={}",
                    envelope.delegationId(), capabilityId, e);
            return new Outcome(DelegationOutcome.PARTIAL, Map.of(), List.of(),
                    "DOMAIN_EXECUTION_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return translate(result, capabilityId);
    }

    /**
     * 把 {@link TaskResult} 译成 A2A 结局。
     *
     * <p>{@code FAILED} 一律译成 {@link DelegationOutcome#PARTIAL} 而不是 FATAL，
     * 除非域侧明确标了 {@code FATAL}:中控语义里的 FAILED 覆盖了「可重试的下游抖动」，
     * 把它译成 FATAL 会让上游对一次网络抖动做终态处理。
     */
    private Outcome translate(TaskResult result, String capabilityId) {
        if (result == null) {
            return new Outcome(DelegationOutcome.PARTIAL, Map.of(), List.of(),
                    "DOMAIN_RESULT_NULL", "域内执行返回 null，结果未知");
        }

        return switch (result.status()) {
            case SUCCESS -> {
                Map<String, Object> facts = factsOf(result, capabilityId);
                // 域侧办成了却没有任何可回传的字段，这里就拦住:
                // 往上传会被网关判 FATAL，而那时日志指向「网关拒了回执」
                yield facts.isEmpty()
                        ? new Outcome(DelegationOutcome.FATAL, Map.of(), List.of(),
                                "DOMAIN_FACTS_EMPTY", "域内 SUCCESS 但 resultPayload 为空")
                        : Outcome.succeeded(facts);
            }
            case NEED_USER -> Outcome.needUser(missingSlotsOf(result));
            case PARTIAL -> new Outcome(DelegationOutcome.PARTIAL, factsOf(result, capabilityId),
                    List.of(), "DOMAIN_PARTIAL", "域内结果未知，上游不得自动重试");
            case CANCELLED -> new Outcome(DelegationOutcome.FATAL, Map.of(), List.of(),
                    "DOMAIN_CANCELLED", "域内已取消");
            case FAILED -> result.failureClass() == Enums.FailureClass.FATAL
                    ? new Outcome(DelegationOutcome.FATAL, Map.of(), List.of(),
                            "DOMAIN_FATAL", "域内终态失败")
                    : new Outcome(DelegationOutcome.PARTIAL, Map.of(), List.of(),
                            "DOMAIN_FAILED", "域内失败 failureClass=" + result.failureClass());
        };
    }

    /**
     * 结构化事实。
     *
     * <p>补一个 {@code capabilityId} 让上游知道这批事实是哪条能力产出的——
     * GOAL 模式下上游并不知道域侧最终选了哪个能力，没有这个字段就无从核对。
     */
    private static Map<String, Object> factsOf(TaskResult result, String capabilityId) {
        if (result.resultPayload().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> facts = new LinkedHashMap<>(result.resultPayload());
        facts.putIfAbsent("capabilityId", capabilityId);
        return facts;
    }

    /**
     * 缺槽。
     *
     * <p>域侧的 {@code resultPayload} 里若带了 {@code missingSlots}，按它翻;
     * 否则给一条兜底槽位。兜底也必须是**结构化的**:回一句面客话术会让入口直接把域侧的话
     * 转给用户，而只有入口该决定对用户说什么（§7 第 1、4 条）。
     */
    @SuppressWarnings("unchecked")
    private static List<DelegationReceipt.MissingSlot> missingSlotsOf(TaskResult result) {
        Object declared = result.resultPayload().get("missingSlots");
        List<DelegationReceipt.MissingSlot> slots = new ArrayList<>();
        if (declared instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object slot = ((Map<String, Object>) map).get("slot");
                    if (slot != null) {
                        slots.add(new DelegationReceipt.MissingSlot(String.valueOf(slot),
                                List.of(), String.valueOf(((Map<String, Object>) map)
                                        .getOrDefault("reasonCode", "MISSING"))));
                    }
                } else if (item != null) {
                    slots.add(new DelegationReceipt.MissingSlot(String.valueOf(item),
                            List.of(), "MISSING"));
                }
            }
        }
        if (slots.isEmpty()) {
            slots.add(new DelegationReceipt.MissingSlot("unspecified", List.of(), "NEED_USER"));
        }
        return slots;
    }

    /**
     * 委托 → 中控任务。
     *
     * <p>{@code idempotencyKey} 直接取 {@code delegationId}:同一次委托重投必须落到
     * 同一把幂等键，否则重投会在域内发第二把键、做第二笔副作用。
     *
     * <p>护栏标为已通过,因为 {@link UnifiedTask} 不允许「带幂等键但护栏未过」。
     * <b>这不代表 A2A 绕过护栏</b>:R1/R2 的确认与限额由目标域自己的护栏在 execute 内部执行,
     * 网关不代跑目标域的护栏（v0.2 §6 不负责清单）。
     */
    private static UnifiedTask taskFor(DelegationEnvelope envelope, String capabilityId) {
        Map<String,Object> parameters = new LinkedHashMap<>(envelope.parameters());
        if (envelope.principal() != null && envelope.principal().authenticated()) {
            parameters.putIfAbsent("principalRef", envelope.principal().principalRef());
        }
        return new UnifiedTask(
                envelope.sourceTaskId(), envelope.traceId(), envelope.intentPath(),
                Enums.InvocationOrigin.A2A,
                envelope.goal(), capabilityId, parameters, null,
                Map.of("confirmedFacts", envelope.confirmedFacts()),
                GuardrailCheck.passed(), envelope.delegationId(), List.of(),
                envelope.deadline());
    }
}
