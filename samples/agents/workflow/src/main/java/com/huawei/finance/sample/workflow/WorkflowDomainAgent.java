package com.huawei.finance.sample.workflow;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.openjiuwen.core.session.WorkflowSessionApi;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowExecutionState;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.core.workflow.WorkflowSessions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 以声明式流程办理任务的领域 Agent。
 *
 * <p>它替掉的是「每个能力手写一个 Service，把查询、提交、失败判断串起来」那部分代码：
 * 顺序与分支在 {@link FlowSpec} 里，执行由 OpenJiuwen 图引擎负责，本类只做两件事——
 * 入口守幂等键，出口把图的结局翻成 {@link TaskResult}。
 *
 * <p><b>刻意不用引擎的中断/续跑。</b>引擎支持在节点里挂起等用户输入、把进度存进检查点，
 * 那套能力很好用，但用在这里会造出第二个状态权威：任务能不能做、做没做过只在中控的库里
 * 有答案，检查点一旦与库不一致，崩溃恢复时就无从判断以谁为准。缺信息一律以
 * {@code NEED_USER} 返回，由中控迁 {@code CLARIFY_PENDING} 并持有会话进度。
 */
public class WorkflowDomainAgent implements DomainAgent {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDomainAgent.class);

    private final Map<String, FlowSpec> specs;
    private final FlowCompiler compiler;

    WorkflowDomainAgent(Map<String, FlowSpec> specs, FlowCompiler compiler) {
        this.specs = Map.copyOf(specs);
        this.compiler = compiler;
        // 启动即编译一遍：声明里的错（操作名找不到、路径引用了后面的步骤）要在启动时炸，
        // 不能等到某个用户办这笔业务时才炸
        this.specs.values().forEach(compiler::compile);
    }

    @Override
    public boolean supports(String capabilityId) {
        return capabilityId != null && specs.containsKey(capabilityId);
    }

    @Override
    public java.util.Set<String> advertisedCapabilities() {
        return specs.keySet();
    }

    /** 已加载的办理流程，供启动自检与运维接口查看「这个进程能办哪些能力、按哪版流程」。 */
    public Map<String, FlowSpec> loadedFlows() {
        return specs;
    }

    @Override
    public TaskResult execute(UnifiedTask task) {
        if (!task.executable()) {
            // 没有幂等键就没有可执行凭据。这道检查在最外层，任何流程声明都改不掉它
            return failure(task, Enums.FailureClass.FATAL, "MISSING_IDEMPOTENCY_KEY",
                    "任务未携带幂等键，拒绝执行");
        }
        FlowSpec spec = specs.get(task.capabilityId());
        if (spec == null) {
            return failure(task, Enums.FailureClass.FATAL, "NO_FLOW_FOR_CAPABILITY",
                    "本进程没有该能力的办理流程：" + task.capabilityId());
        }

        FlowState state = new FlowState(task);
        // 会话按幂等键做标识而不是按 taskId：中控重放同一笔业务时用的是同一把键，
        // 这样引擎侧的 Trace 也能跟对账口径对齐
        WorkflowSessionApi session = WorkflowSessions.createWorkflowSession("agent-platform-" + task.idempotencyKey());
        try {
            // 每次执行都重新编译一张图：Workflow 实例在 invoke 过程中会改自身状态
            // （endCompId、isStreaming、图执行状态的重置），并发共用一个实例是数据竞争。
            // 领域 Agent 必然被并发调用，编译一张图只是若干次对象分配，不值得为省这点开销冒这个风险
            WorkflowOutput output = compiler.compile(spec).invoke(Map.of(FlowState.KEY, state), session, null);
            return interpret(task, output, state);
        } catch (RuntimeException e) {
            return unwrap(task, e, state);
        }
    }

    /**
     * 把图的结局翻成任务结论。
     *
     * <p>只认 COMPLETED。引擎还会返回 INPUT_REQUIRED（有节点在等用户输入），
     * 而本模块不用中断能力，出现它说明有人在流程里加了 {@code interact} 调用——
     * 那条路径上的进度只存在于检查点里，中控完全不知道，必须当失败处理而不是当成功往下走。
     */
    private TaskResult interpret(UnifiedTask task, WorkflowOutput output, FlowState state) {
        if (output == null || output.getState() != WorkflowExecutionState.COMPLETED) {
            String actual = output == null ? "null" : String.valueOf(output.getState());
            log.error("办理流程未正常结束 capability={} task={} 图状态={}",
                    task.capabilityId(), task.taskId(), actual);
            return failure(task, Enums.FailureClass.FATAL, "FLOW_NOT_COMPLETED",
                    "办理流程未正常结束，图状态=" + actual);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (output.getResult() instanceof Map<?, ?> result) {
            result.forEach((k, v) -> payload.put(String.valueOf(k), v));
        }
        payload.put("flowVersion", specs.get(task.capabilityId()).version());
        log.info("办理完成 capability={} task={} 已执行步骤={}",
                task.capabilityId(), task.taskId(), state.steps().keySet());
        return new TaskResult(task.taskId(), Enums.TaskStatus.SUCCESS, Enums.FailureClass.NONE,
                payload, task.idempotencyKey(), task.guardrailCheck());
    }

    /**
     * 取回失败归类。
     *
     * <p>先看流程状态（节点抛错前把归类落在那里），cause 链只作为兜底再看一眼——
     * 引擎会把节点异常转成自己的错误对象且不保留 cause，所以链上通常什么都挖不到。
     *
     * <p>两处都拿不到就归 FATAL：一个我们没预料到的引擎内部异常，最不该做的事是当成可重试
     * 让中控再发一次——那笔业务在下游可能已经成立了。
     */
    private TaskResult unwrap(UnifiedTask task, RuntimeException e, FlowState state) {
        if (state.failure() != null) {
            return classified(task, state.failure());
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof StepFailure failure) {
                return classified(task, failure);
            }
        }
        log.error("办理流程抛出未归类异常 capability={} task={}", task.capabilityId(), task.taskId(), e);
        return failure(task, Enums.FailureClass.FATAL, "FLOW_ENGINE_ERROR", e.toString());
    }

    private TaskResult classified(UnifiedTask task, StepFailure failure) {
        Enums.FailureClass failureClass = switch (failure.classification()) {
            case RETRYABLE -> Enums.FailureClass.RETRYABLE;
            case NEED_USER -> Enums.FailureClass.NEED_USER;
            case FATAL -> Enums.FailureClass.FATAL;
        };
        Enums.TaskStatus status = failureClass == Enums.FailureClass.NEED_USER
                ? Enums.TaskStatus.NEED_USER
                : Enums.TaskStatus.FAILED;
        log.warn("办理失败 capability={} task={} 归类={} 原因={}",
                task.capabilityId(), task.taskId(), failureClass, failure.getMessage());
        return new TaskResult(task.taskId(), status, failureClass,
                Map.of("error", failure.getMessage()), task.idempotencyKey(), task.guardrailCheck());
    }

    private TaskResult failure(UnifiedTask task, Enums.FailureClass failureClass, String code, String detail) {
        // 无幂等键的失败不回传幂等键：那条路径上本来就没有凭据可回
        String key = task.executable() ? task.idempotencyKey() : null;
        return new TaskResult(task.taskId(), Enums.TaskStatus.FAILED, failureClass,
                Map.of("error", code, "detail", detail), key, task.guardrailCheck());
    }
}
