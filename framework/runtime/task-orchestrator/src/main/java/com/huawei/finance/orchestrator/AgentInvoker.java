package com.huawei.finance.orchestrator;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.CapabilityDelegator;
import com.huawei.finance.contracts.port.DomainAgent;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 领域 Agent 调用，带主控侧强制超时（FP-26a）。
 *
 * <p>在此之前，能力卡上的 {@code timeoutMs} 是一个**没有消费点的字段**：值写在卡上、
 * JSON Schema 也校验它 {@code minimum: 1}，但调用处直接 {@code agent.execute(task)} 同步等到底，
 * 而 {@code UnifiedTask.deadline} 被硬编码成 30 秒且全仓库无人读取。外部同类系统一次派发
 * 干等 74 秒无切断（§2.7.4），我们的形态更隐蔽——看起来处处都配好了。
 *
 * <p><b>为什么上限必须由主控定。</b>能力卡由领域方维护，超时值也归他们填。让领域方自己
 * 声明"我要 999 秒"并照做，等于把用户的等待时长交给被调方决定。主控取
 * {@code min(卡上声明, 主控上限)}，声明值只能更小。
 *
 * <p><b>超时不等于没执行。</b>这是本类最要紧的一条。中断线程并不能撤回一笔已经发出去的
 * 转账，所以对有副作用的能力，超时的正确表述是「结果未知」而不是「失败」——
 * 回一句"办理失败，请重试"会直接诱导用户再转一次。因此按能力有无副作用分流：
 *
 * <ul>
 *   <li>有副作用（转账、还款这类）：{@code failureClass = PARTIAL}，走对账与人工，
 *       不给重试引导，交由 FP-27 的补偿矩阵收口；
 *   <li>无副作用（查询）：{@code failureClass = RETRYABLE}，重试是安全的。
 * </ul>
 *
 * <p>用独立线程而非依赖下游客户端自带的超时，是因为主控不应假设领域方一定配了超时——
 * 这道防线的意义恰恰在于对方没配的时候。代价是一次线程切换，以及
 * {@code RequestContextHolder} 这类 ThreadLocal 必须显式搬运。
 */
public class AgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(AgentInvoker.class);

    private final List<DomainAgent> agents;
    private final ExecutorService executor;
    private final OrchestratorProperties props;
    private final MeterRegistry meterRegistry;

    /** 委托通道；未装时为 null，本类退回纯本地调用。 */
    private final CapabilityDelegator delegator;

    public AgentInvoker(List<DomainAgent> agents, ExecutorService executor,
                        OrchestratorProperties props, MeterRegistry meterRegistry) {
        this(agents, executor, props, meterRegistry, null);
    }

    public AgentInvoker(List<DomainAgent> agents, ExecutorService executor,
                        OrchestratorProperties props, MeterRegistry meterRegistry,
                        CapabilityDelegator delegator) {
        this.agents = agents;
        this.executor = executor;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.delegator = delegator;
    }

    /**
     * 计算这次调用实际生效的超时。
     *
     * <p>能力卡声明值只能更小；声明得比上限大时按上限执行并打点——那是一张需要业务复核的卡，
     * 静默改写它的语义而不留痕，下次没人知道卡上写的数其实没生效。
     */
    public long effectiveTimeoutMs(CapabilityCard card) {
        long ceiling = props.getAgentTimeoutCeilingMs();
        if (card == null || card.timeoutMs() <= 0) {
            return ceiling;
        }
        if (card.timeoutMs() > ceiling) {
            meterRegistry.counter(AgentMetrics.AGENT_TIMEOUT_CLAMPED,
                    AgentMetrics.TAG_CAPABILITY, card.capabilityId()).increment();
            log.warn("能力卡声明的超时超出主控上限，按上限执行 capability={} 声明={}ms 上限={}ms",
                    card.capabilityId(), card.timeoutMs(), ceiling);
            return ceiling;
        }
        return card.timeoutMs();
    }

    public TaskResult invoke(UnifiedTask task, CapabilityCard card) {
        DomainAgent agent = agents.stream()
                .filter(a -> a.supports(task.capabilityId()))
                .findFirst()
                .orElse(null);

        // A2A 入站已明确到达目标 Agent：本地承接优先，避免再次委托给自己形成环路。
        // FAST/SLOW 只描述此前的意图识别路径，不参与执行通道选择。
        if (task.invocationOrigin() == Enums.InvocationOrigin.A2A && agent != null) {
            return invokeLocal(task, card, agent);
        }
        if (agent == null || task.invocationOrigin() != Enums.InvocationOrigin.A2A) {
            Optional<TaskResult> delegated = tryDelegate(task, card);
            if (delegated.isPresent()) {
                return delegated.get();
            }
        }

        // 委托发生在护栏、租约闸与幂等键**之后**——
        // 那是本方法被调用的位置决定的，不是本方法自己判的。
        //
        // 把「谁去办」放在这里而不是放在 orchestrator.handle 外面，是这段的全部意义:
        // 挂在外面时，委托与中控二选一，于是换一条执行通道顺带换掉了护栏、
        // CONTEXT_UNAVAILABLE 副作用闸和本地任务建档（草案 §8.5、§12 第 4 条）
        if (agent == null) {
            log.error("没有领域 Agent 承接能力 {}", task.capabilityId());
            return failed(task, Enums.FailureClass.FATAL, "NO_AGENT_FOR_CAPABILITY");
        }

        return invokeLocal(task, card, agent);
    }

    private TaskResult invokeLocal(UnifiedTask task, CapabilityCard card, DomainAgent agent) {
        long timeoutMs = effectiveTimeoutMs(card);

        // 两样东西都不会自己跟着线程池走，都得手搬：
        // 下面这段与委托无关，委托走的是自己的传输与超时预算
        // RequestContext 不搬，工作线程里的网关往返计不进本次请求，序列恒为空；
        // MDC 不搬，领域调用期间的日志行首没有 traceId，而那正是出问题时最需要它的一段。
        RequestContext ctx = RequestContextHolder.get();
        Map<String, String> mdc = MDC.getCopyOfContextMap();

        Future<TaskResult> future = executor.submit(() -> {
            RequestContextHolder.set(ctx);
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return agent.execute(task);
            } finally {
                RequestContextHolder.clear();
                MDC.clear();
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);

        } catch (TimeoutException e) {
            future.cancel(true);
            return onTimeout(task, card, timeoutMs);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failed(task, Enums.FailureClass.RETRYABLE, "INTERRUPTED");

        } catch (java.util.concurrent.ExecutionException e) {
            // 领域 Agent 抛异常不能穿透到用户。转成 TaskResult，
            // 回复层才有东西可渲染，任务状态也才走得到终态
            Throwable cause = e.getCause() == null ? e : e.getCause();
            log.error("领域 Agent 执行异常 task={} cause={}", task.taskId(), cause.toString());
            return failed(task, Enums.FailureClass.FATAL, cause.getClass().getSimpleName());
        }
    }

    /**
     * 试委托。
     *
     * <p>返回空的两种情形语义相同——「这条路走不通」，回落本地执行:
     * 没装委托通道、或者委托方明确表示目标没收到这笔业务。
     *
     * <p><b>委托方抛异常不在这里被吞成「走不通」。</b>异常穿透出去由中控按失败落状态:
     * 抛异常意味着我们不知道目标收到了没有，而回落本地执行等于在目标可能已经动手的
     * 情况下再办一次。走不通与结果未知的回落方向相反，不能合并。
     */
    private Optional<TaskResult> tryDelegate(UnifiedTask task, CapabilityCard card) {
        if (delegator == null || !delegator.handles(task.capabilityId())) {
            return Optional.empty();
        }

        Optional<TaskResult> result = delegator.delegate(task, card);
        if (result.isEmpty()) {
            log.info("委托走不通，回落本地执行 task={} capability={}",
                    task.taskId(), task.capabilityId());
        }
        return result;
    }

    private TaskResult onTimeout(UnifiedTask task, CapabilityCard card, long timeoutMs) {
        boolean sideEffecting = hasSideEffects(card);
        Enums.FailureClass failureClass =
                sideEffecting ? Enums.FailureClass.PARTIAL : Enums.FailureClass.RETRYABLE;

        meterRegistry.counter(AgentMetrics.AGENT_TIMEOUT,
                AgentMetrics.TAG_CAPABILITY, task.capabilityId(),
                AgentMetrics.TAG_OUTCOME, failureClass.name()).increment();
        log.error("领域 Agent 超时 task={} capability={} 超时={}ms 副作用={} 判定={}",
                task.taskId(), task.capabilityId(), timeoutMs, sideEffecting, failureClass);

        return failed(task, failureClass, "TIMEOUT");
    }

    /**
     * 判断能力是否有副作用。
     *
     * <p>以能力卡的 {@code sideEffects} 为准，风险等级只作兜底：R2 就是「涉及资金变动」，
     * 一张 R2 的卡忘了写 sideEffects 时，按无副作用处理会让超时话术引导用户重试转账。
     * 这一处宁可判错方向也不能判错代价。
     */
    private static boolean hasSideEffects(CapabilityCard card) {
        if (card == null) {
            return true;
        }
        return !card.sideEffects().isEmpty() || card.riskLevel().requiresExplicitConfirmation();
    }

    private static TaskResult failed(UnifiedTask task, Enums.FailureClass failureClass, String error) {
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, failureClass,
                Map.of("error", error), task.idempotencyKey(), task.guardrailCheck());
    }
}
