package com.huawei.finance.fastpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.WorkflowSessionApi;
import com.openjiuwen.core.session.internal.WrappedSession;
import com.openjiuwen.core.workflow.ComponentComposable;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowSessions;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.MDC;

/**
 * 快路径的编排交给 OpenJiuwen 图引擎。
 *
 * <p>三级短路是**条件边**而不是节点内的 if：跳过的步骤在 trace 里根本没有节点记录，
 * 而「进去了又空转返回」在 trace 里与真的执行过长得一样。出口分布异常时要回答
 * 「这批请求到底走没走召回」，靠的就是这个差别。
 *
 * <pre>
 * start → rewrite → event ─┬─(续轮且置信足够)→ continuation ────────────────→ end
 *                          └→ cache ─┬─(命中)──────────────────────────────→ end
 *                                    └→ rules ─┬─(命中强规则)───────────────→ end
 *                                              └→ recall → arbitration ────→ end
 * </pre>
 *
 * <p>行为必须与 {@link SequentialFastPath} 逐位一致，两者共用同一份 {@link FastPathSteps}，
 * 差别只在这张图的连法。{@code FastPathParityTest} 逐条比对。
 */
class FastPathGraph {

    private static final String START = "__start";
    private static final String REWRITE = "rewrite";
    private static final String EVENT = "event";
    private static final String CONTINUATION = "continuation";
    private static final String CACHE = "cache";
    private static final String RULES = "rules";
    private static final String RECALL = "recall";
    private static final String ARBITRATION = "arbitration";
    private static final String END = "__end";

    private final FastPathSteps steps;

    FastPathGraph(FastPathSteps steps) {
        this.steps = steps;
    }

    FastPathResult decide(FastPathRequest request) {
        FastPathState state = new FastPathState(request);
        // 会话标识取 traceId：图引擎侧的记录与我们的日志、APM 用同一个 id 才对得上
        WorkflowSessionApi session = WorkflowSessions.createWorkflowSession(
                "fp-" + request.ctx().traceId());
        compile().invoke(Map.of(FastPathState.KEY, state), session, null);
        if (state.result() == null) {
            // 图跑完了却没有出口，说明连边漏了一条。这时最不该做的是编一个兜底出口返回，
            // 那会让一个编排缺陷表现成「模型今天判得怪」
            throw new IllegalStateException("快路径图执行结束但没有产出出口，trace="
                    + request.ctx().traceId());
        }
        return state.result();
    }

    /**
     * 每次执行重新编译一张图。
     *
     * <p>{@code Workflow} 实例在 {@code invoke} 过程中会改自身状态（endCompId、流式标记、
     * 图执行状态的重置），并发共用一个实例是数据竞争，而快路径必然被并发调用。
     * 实测编译一张六步图的 p99 是 0.078ms（{@code GraphOverheadProbeTest}），
     * 相对整条链路的毫秒级预算可以忽略，不值得为省这点开销去做实例池。
     */
    private Workflow compile() {
        Workflow workflow = new Workflow();
        workflow.setStartComp(START, node(s -> { }), null, null);
        workflow.addWorkflowComp(REWRITE, node(steps::rewrite), null, null);
        workflow.addWorkflowComp(EVENT, node(steps::classifyEvent), null, null);
        workflow.addWorkflowComp(CONTINUATION, node(steps::continuation), null, null);
        workflow.addWorkflowComp(CACHE, node(this::cacheStep), null, null);
        workflow.addWorkflowComp(RULES, node(steps::strongRules), null, null);
        workflow.addWorkflowComp(RECALL, node(steps::recall), null, null);
        workflow.addWorkflowComp(ARBITRATION, node(steps::arbitrate), null, null);
        workflow.setEndComp(END, node(s -> { }), null, null);

        workflow.addConnection(START, REWRITE);
        workflow.addConnection(REWRITE, EVENT);
        // 续轮短路：判据在事件分类结论上，路由时求值
        workflow.addConditionalConnection(EVENT,
                route(state -> steps.continuationApplies(state) ? CONTINUATION : CACHE));
        workflow.addConnection(CONTINUATION, END);
        // 一级与二级短路都是「已经有出口就直奔终点」
        workflow.addConditionalConnection(CACHE, route(state -> state.decided() ? END : RULES));
        workflow.addConditionalConnection(RULES, route(state -> state.decided() ? END : RECALL));
        workflow.addConnection(RECALL, ARBITRATION);
        workflow.addConnection(ARBITRATION, END);
        return workflow;
    }

    /**
     * 缓存这一步把「算键」与「查缓存」合在一个节点里。
     *
     * <p>合并是因为槽位合并与缓存键计算没有分支意义，单独拆一个节点只会在 trace 上多一条
     * 恒定出现的记录。而「查不查缓存」这个判断留在节点内——它不是路由：澄清重试时这一步
     * 仍要算槽位与键（后面写缓存要用），只是不读。
     */
    private void cacheStep(FastPathState state) {
        steps.mergeSlotsAndKey(state);
        if (steps.cacheEnabled(state)) {
            steps.cacheLookup(state);
        }
    }

    private static StepNode node(Consumer<FastPathState> action) {
        return new StepNode(action);
    }

    private static Function<Object, Object> route(Function<FastPathState, String> router) {
        return session -> router.apply(stateOf(session));
    }

    /**
     * 从路由回调里取状态。
     *
     * <p>引擎传给条件边的是它内部的会话包装类型，因此这里依赖了 {@code core.session.internal}
     * 下的公开基类——与 {@code workflow} 是同一处依赖。升 OJ 版本优先回归它：
     * 取不到状态时直接抛，绝不让条件在无状态下退化成某个默认分支，
     * 那会让所有请求都走同一条路而不报错。
     */
    private static FastPathState stateOf(Object session) {
        Object state = session instanceof WrappedSession wrapped
                ? wrapped.getGlobalState(FastPathState.KEY)
                : null;
        if (state instanceof FastPathState fastPathState) {
            return fastPathState;
        }
        throw new IllegalStateException("条件边取不到快路径状态，会话类型="
                + (session == null ? "null" : session.getClass().getName()));
    }

    /**
     * 把一个步骤接到图上。这里没有业务判断，也不该有。
     *
     * <p>唯一多做的一件事是**把调用上下文搬到执行线程上**：图引擎不在调用者线程上跑节点，
     * 而 {@code RequestContext} 与 MDC 都是 ThreadLocal。不搬的后果是两条硬约束当场失效——
     * 网关往返计不进本次请求（往返序列恒为空），日志行首没有 traceId。
     * 这不是假想：改成图执行后 {@code GatewayBudgetTest} 立刻红了三条，
     * 而它们红的方式是「一次调用都没记到」，不是数字差一。
     */
    private static final class StepNode extends ComponentExecutable implements ComponentComposable {

        private final Consumer<FastPathState> action;

        private StepNode(Consumer<FastPathState> action) {
            this.action = action;
        }

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            FastPathState state = (FastPathState) session.getGlobalState(FastPathState.KEY);
            if (state == null) {
                throw new IllegalStateException("快路径状态缺失，说明入参没挂上全局态");
            }
            FastPathState.Carried carried = state.carried();
            RequestContext previous = RequestContextHolder.get();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            RequestContextHolder.set(carried.ctx());
            if (carried.mdc() != null) {
                MDC.setContextMap(carried.mdc());
            }
            try {
                action.accept(state);
            } finally {
                // 还原而不是直接 clear：节点也可能恰好跑在调用者线程上（引擎不保证换线程），
                // 那时 clear 会把调用者自己的上下文抹掉，后果与不搬一样严重
                restore(previous, previousMdc);
            }
            // 重新写回：全局态的实现可能是拷贝语义，只改对象内部字段不保证被持有方看见
            session.updateGlobalState(Map.of(FastPathState.KEY, state));
            return Map.of();
        }

        private static void restore(RequestContext previous, Map<String, String> previousMdc) {
            if (previous == null) {
                RequestContextHolder.clear();
            } else {
                RequestContextHolder.set(previous);
            }
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }
}
