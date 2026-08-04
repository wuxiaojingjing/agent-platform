package com.huawei.finance.oj.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.WorkflowSessionApi;
import com.openjiuwen.core.workflow.ComponentComposable;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowSessions;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图执行到底要花多少时间：决定快路径能不能上图的那道闸门。
 *
 * <p>快路径是面客同步链路，整条预算是毫秒级。{@code workflow} 那边确认过
 * {@code Workflow} 实例在 {@code invoke} 中会改自身状态，不能并发共用，所以那里是**每次执行
 * 重新编译一张图**；领域办理是异步的，几百微秒无所谓，快路径不是。
 *
 * <p>这个探针量两件事：编译一张图要多久，执行一次空图要多久。两者之和就是上图的固定成本。
 * 门槛定在 p99 增量 2ms——超过就不做第二波，因为省下的编排代码换不来一次面客延迟劣化。
 *
 * <p>断言写得比实测宽（10ms），因为 CI 机器与开发机差着数量级，把断言卡在实测值上只会
 * 得到一条随机变红的用例。真正的判断依据是它打印出来的数，记在文档里。
 */
class GraphOverheadProbeTest {

    private static final int WARMUP = 50;
    private static final int ROUNDS = 500;

    /** 空节点：量的是引擎的调度开销，不是业务耗时。 */
    private static final class NoopNode extends ComponentExecutable implements ComponentComposable {

        @Override
        public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
            return Map.of();
        }
    }

    /** 快路径那张图的规模：改写、事件分类、缓存、强规则、召回、仲裁，六步上下。 */
    private static Workflow compile() {
        Workflow workflow = new Workflow();
        workflow.setStartComp("start", new NoopNode(), null, null);
        String previous = "start";
        for (String step : new String[] {"rewrite", "event", "cache", "rules", "recall", "arbitration"}) {
            workflow.addWorkflowComp(step, new NoopNode(), null, null);
            workflow.addConnection(previous, step);
            previous = step;
        }
        workflow.setEndComp("end", new NoopNode(), null, null);
        workflow.addConnection(previous, "end");
        return workflow;
    }

    @Test
    @DisplayName("编译加执行一张六步图的固定成本")
    void measureCompileAndInvokeCost() {
        for (int i = 0; i < WARMUP; i++) {
            runOnce(i);
        }

        long[] compileNanos = new long[ROUNDS];
        long[] totalNanos = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            long start = System.nanoTime();
            Workflow workflow = compile();
            long compiled = System.nanoTime();
            invoke(workflow, i);
            long done = System.nanoTime();
            compileNanos[i] = compiled - start;
            totalNanos[i] = done - start;
        }

        Arrays.sort(compileNanos);
        Arrays.sort(totalNanos);
        double compileP99 = compileNanos[(int) (ROUNDS * 0.99)] / 1_000_000.0;
        double totalP50 = totalNanos[ROUNDS / 2] / 1_000_000.0;
        double totalP99 = totalNanos[(int) (ROUNDS * 0.99)] / 1_000_000.0;

        System.out.printf("图固定成本：编译 p99=%.3fms，编译+执行 p50=%.3fms p99=%.3fms%n",
                compileP99, totalP50, totalP99);

        assertThat(totalP99)
                .as("这是快路径上图的固定成本。断言宽是为了不在 CI 上随机变红；"
                        + "真正的判断看打印值，超过 2ms 就不该上图")
                .isLessThan(10.0);
    }

    private static void runOnce(int seq) {
        invoke(compile(), seq);
    }

    private static void invoke(Workflow workflow, int seq) {
        WorkflowSessionApi session = WorkflowSessions.createWorkflowSession("probe-" + seq);
        workflow.invoke(Map.of(), session, null);
    }
}
