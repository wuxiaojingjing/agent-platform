package com.huawei.finance.sample.workflow;

import com.huawei.finance.contracts.model.UnifiedTask;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流程执行期的状态载体，只在一次任务执行内存活。
 *
 * <p>刻意自己维护一个扁平的「步骤 id → 产出」表，而不是靠图引擎自带的状态传递。
 * 引擎的 {@code inputsSchema} 若图省事写成「把整个状态捞过来」，每个节点的产出里
 * 就会嵌着它的入参，入参里又嵌着上游的产出——三步之后状态呈指数膨胀。实测确认过这个行为。
 * 状态是要序列化的（检查点、Trace），膨胀的代价不只是内存。
 *
 * <p>放在会话全局态里的键固定为 {@link #KEY}，加前缀是因为这块状态与行内其他
 * 往全局态里写东西的代码共用一个命名空间。
 */
final class FlowState {

    static final String KEY = "__agent_flow_state";

    private final UnifiedTask task;
    private final Map<String, Object> steps = new LinkedHashMap<>();
    private StepFailure failure;

    FlowState(UnifiedTask task) {
        this.task = task;
    }

    /**
     * 记下这次失败的归类。
     *
     * <p>为什么失败归类要挂在状态上而不是靠异常往外传：图引擎会把节点抛出的异常转成它自己的
     * 错误对象，只保留字符串化的 reason，cause 链上取不到原异常（实测确认）。
     * 于是「这次失败是可重试还是致命」这个信息若只写在异常里，出了引擎就没了，
     * 而它决定中控要不要重投——丢了它，一次下游抖动会被当成致命失败直接告诉用户办不了。
     */
    void fail(StepFailure stepFailure) {
        this.failure = stepFailure;
    }

    StepFailure failure() {
        return failure;
    }

    UnifiedTask task() {
        return task;
    }

    Map<String, Object> steps() {
        return steps;
    }

    void record(String stepId, Map<String, Object> output) {
        steps.put(stepId, output == null ? Map.of() : Map.copyOf(output));
    }

    OperationContext context() {
        return new OperationContext(task, steps);
    }

    /**
     * 按路径取值。
     *
     * <p>支持两种前缀：{@code params.x} 取任务参数，{@code <步骤 id>.x} 取某步产出；
     * 更深的层级按点号继续下钻。刻意不支持数组下标与函数调用——那会让结果映射变成
     * 另一门没人测过的小语言。
     */
    Object resolve(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("\\.");
        Object current = "params".equals(parts[0]) ? task.parameters() : steps.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            current = current instanceof Map<?, ?> map ? map.get(parts[i]) : null;
        }
        return current;
    }

    /**
     * 路径取值是否为真。
     *
     * <p>{@code null}、{@code false}、空串、字符串 "false" 都算假，其余算真。
     * 把空串算作假是为了对上「下游没返回这个字段」与「返回了空」这两种在业务上同义的情形。
     */
    boolean truthy(String path) {
        Object value = resolve(path);
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank() && !"false".equalsIgnoreCase(s);
        }
        return true;
    }
}
