package com.huawei.finance.sample.workflow;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.internal.WrappedSession;
import com.openjiuwen.core.workflow.ComponentComposable;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.Workflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把流程声明编译成一张 OpenJiuwen 图。
 *
 * <p>编译期就把能查的错查完：操作名找不到实现、步骤 id 重复、条件引用了后面才产生的步骤，
 * 都在这里失败。这些错误若留到运行期，暴露方式是某个用户的某一笔业务办到一半停住，
 * 而不是一次启动失败。
 */
final class FlowCompiler {

    private static final String START = "__start";
    private static final String END = "__end";

    private final Map<String, DomainOperation> operations;

    FlowCompiler(List<DomainOperation> operations) {
        Map<String, DomainOperation> byName = new LinkedHashMap<>();
        for (DomainOperation op : operations) {
            DomainOperation previous = byName.put(op.name(), op);
            if (previous != null) {
                throw new IllegalStateException("操作名重复：" + op.name()
                        + "，实现类 " + previous.getClass().getName() + " 与 " + op.getClass().getName());
            }
        }
        this.operations = byName;
    }

    Workflow compile(FlowSpec spec) {
        validate(spec);

        Workflow workflow = new Workflow();
        // 入参不走图引擎的状态传递：任务与各步产出统一挂在会话全局态的 FlowState 上，
        // 理由见 FlowState 的类注释（状态套娃）
        workflow.setStartComp(START, new PassThroughNode(), null, null);
        for (FlowSpec.Step step : spec.steps()) {
            workflow.addWorkflowComp(step.id(), new OperationNode(step, operations.get(step.operation())),
                    null, null);
        }
        workflow.setEndComp(END, new ResultNode(spec), null, null);

        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(START);
        spec.steps().forEach(s -> nodeIds.add(s.id()));
        nodeIds.add(END);

        for (int i = 0; i < nodeIds.size() - 1; i++) {
            connect(workflow, spec, nodeIds, i);
        }
        return workflow;
    }

    /**
     * 连一条边。
     *
     * <p>下一步带条件时用条件边，让引擎在路由时决定去哪个节点——而不是让节点自己进去了再判断
     * 「这步该不该做」。差别在可观测性：跳过的步骤在 Trace 里根本没有节点记录，
     * 而「进去了又空转返回」在 Trace 里和真的执行过长得一样。
     */
    private void connect(Workflow workflow, FlowSpec spec, List<String> nodeIds, int index) {
        String from = nodeIds.get(index);
        String immediateNext = nodeIds.get(index + 1);

        if (!conditional(spec, immediateNext)) {
            workflow.addConnection(from, immediateNext);
            return;
        }

        // 条件在路由时才求值：后面若连着好几个带条件的步骤，要一路跳到第一个条件成立的那个
        Function<Object, Object> router = session -> firstEligible(spec, nodeIds, index + 1, session);
        workflow.addConditionalConnection(from, router);
    }

    private String firstEligible(FlowSpec spec, List<String> nodeIds, int from, Object session) {
        FlowState state = flowState(session);
        for (int i = from; i < nodeIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            FlowSpec.Step step = stepOf(spec, nodeId);
            if (step == null || eligible(step, state)) {
                return nodeId;
            }
        }
        return END;
    }

    private static boolean eligible(FlowSpec.Step step, FlowState state) {
        if (step.when() != null && !state.truthy(step.when())) {
            return false;
        }
        return step.unless() == null || !state.truthy(step.unless());
    }

    /**
     * 从路由回调拿到流程状态。
     *
     * <p>引擎传给条件边的是它内部的会话包装类型（{@code RouterSession}），因此这里依赖了
     * {@code core.session.internal} 下的公开基类。这是本模块唯一一处碰引擎内部包的地方，
     * 升级 OJ 版本时优先回归这一处：拿不到状态时直接抛，不让条件在无状态下退化成「恒真」。
     */
    private static FlowState flowState(Object session) {
        Object state = session instanceof WrappedSession wrapped
                ? wrapped.getGlobalState(FlowState.KEY)
                : null;
        if (state instanceof FlowState flowState) {
            return flowState;
        }
        throw StepFailure.fatal("条件边取不到流程状态，会话类型="
                + (session == null ? "null" : session.getClass().getName()));
    }

    private static boolean conditional(FlowSpec spec, String nodeId) {
        FlowSpec.Step step = stepOf(spec, nodeId);
        return step != null && (step.when() != null || step.unless() != null);
    }

    private static FlowSpec.Step stepOf(FlowSpec spec, String nodeId) {
        return spec.steps().stream().filter(s -> s.id().equals(nodeId)).findFirst().orElse(null);
    }

    private void validate(FlowSpec spec) {
        if (spec.capabilityId() == null || spec.capabilityId().isBlank()) {
            throw new IllegalStateException("流程声明缺 capabilityId");
        }
        if (spec.steps().isEmpty()) {
            throw new IllegalStateException("流程声明没有任何步骤：" + spec.capabilityId());
        }

        List<String> seen = new ArrayList<>();
        for (FlowSpec.Step step : spec.steps()) {
            if (step.id() == null || step.id().isBlank()) {
                throw new IllegalStateException("步骤缺 id：" + spec.capabilityId());
            }
            if (START.equals(step.id()) || END.equals(step.id())) {
                throw new IllegalStateException("步骤 id 与保留节点冲突：" + step.id());
            }
            if (seen.contains(step.id())) {
                throw new IllegalStateException("步骤 id 重复：" + step.id());
            }
            if (!operations.containsKey(step.operation())) {
                throw new IllegalStateException("找不到操作实现 operation=" + step.operation()
                        + " step=" + step.id() + " 已注册=" + operations.keySet());
            }
            assertResolvable(spec, step.when(), seen, step.id());
            assertResolvable(spec, step.unless(), seen, step.id());
            seen.add(step.id());
        }
        spec.result().forEach((field, path) -> assertResolvable(spec, path, seen, "result." + field));
    }

    /**
     * 条件与结果映射只允许引用「已经跑过的步骤」或任务参数。
     *
     * <p>引用后面才产生的步骤，运行时取到的永远是 null——条件恒假、结果字段恒空，
     * 而两者都不会报错。这类错误在测试环境里也很难被发现，因为空值往下游看起来像是「没这个字段」。
     */
    private static void assertResolvable(FlowSpec spec, String path, List<String> availableSteps, String where) {
        if (path == null || path.isBlank()) {
            return;
        }
        String root = path.split("\\.")[0];
        if ("params".equals(root) || availableSteps.contains(root)) {
            return;
        }
        boolean laterStep = spec.steps().stream().anyMatch(s -> root.equals(s.id()));
        throw new IllegalStateException(laterStep
                ? "路径引用了尚未执行的步骤 path=" + path + " 出现在 " + where
                : "路径引用了不存在的步骤 path=" + path + " 出现在 " + where);
    }

    /** 起点占位：状态已在全局态里，起点不需要做任何事。 */
    private static final class PassThroughNode extends ComponentExecutable implements ComponentComposable {

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            return Map.of();
        }
    }

    /** 终点：按结果映射拼出 {@code TaskResult.resultPayload}。 */
    private static final class ResultNode extends ComponentExecutable implements ComponentComposable {

        private final FlowSpec spec;

        private ResultNode(FlowSpec spec) {
            this.spec = spec;
        }

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            FlowState state = (FlowState) session.getGlobalState(FlowState.KEY);
            if (state == null) {
                throw StepFailure.fatal("汇总阶段取不到流程状态");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            spec.result().forEach((field, path) -> {
                Object value = state.resolve(path);
                if (value != null) {
                    payload.put(field, value);
                }
            });
            return payload;
        }
    }
}
