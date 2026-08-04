package com.huawei.finance.sample.workflow;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentComposable;
import com.openjiuwen.core.workflow.ComponentExecutable;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把一个叶子操作接到 OpenJiuwen 图上的适配器。
 *
 * <p>这个类里没有业务判断，也不该有：它只负责取状态、调操作、记产出、把失败归类成
 * {@link StepFailure}。办理顺序与分支由图声明，业务动作在 {@link DomainOperation} 里，
 * 两头都不在这儿——这也是本模块能把「每个能力重写一遍编排」压成「写几个叶子操作」的原因。
 */
final class OperationNode extends ComponentExecutable implements ComponentComposable {

    private static final Logger log = LoggerFactory.getLogger(OperationNode.class);

    private final FlowSpec.Step step;
    private final DomainOperation operation;

    OperationNode(FlowSpec.Step step, DomainOperation operation) {
        this.step = step;
        this.operation = operation;
    }

    @Override
    public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
        FlowState state = (FlowState) session.getGlobalState(FlowState.KEY);
        if (state == null) {
            // 状态没挂上就往下走，等于让操作看不到任务与幂等键去调下游
            throw StepFailure.fatal("流程状态缺失，step=" + step.id());
        }

        Map<String, Object> output;
        try {
            output = operation.execute(state.context());
        } catch (StepFailure e) {
            state.fail(e);
            throw e;
        } catch (RuntimeException e) {
            // 操作没表态时按声明归类。默认 FATAL，见 FlowSpec.OnError
            log.warn("办理步骤失败 step={} operation={} 归类={} 原因={}",
                    step.id(), operation.name(), step.onError(), e.toString());
            StepFailure failure = new StepFailure(step.onError(),
                    "步骤 " + step.id() + " 执行失败：" + e.getMessage(), e);
            // 先落状态再抛：归类信息过不了引擎的异常包装，见 FlowState#fail
            state.fail(failure);
            throw failure;
        }

        state.record(step.id(), output);
        // 重新写回：全局态的实现可能是拷贝语义，只改对象内部字段不保证被持有方看见
        session.updateGlobalState(Map.of(FlowState.KEY, state));
        return output == null ? Map.of() : output;
    }
}
