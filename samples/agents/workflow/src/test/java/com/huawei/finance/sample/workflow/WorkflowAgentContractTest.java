package com.huawei.finance.sample.workflow;

import com.huawei.finance.contracts.port.DomainAgent;
import com.huawei.finance.tck.DomainAgentContract;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 声明式流程产出的 Agent 也要过 TCK。
 *
 * <p>换实现方式不等于换契约：不管办理逻辑是手写的 Service 还是一张图，
 * 中控对它的假设（幂等键为准、回显 taskId、失败走 TaskResult）一条都不放宽。
 *
 * <p>TCK 里的「同一任务重投两次结果一致」这条，靠的是叶子操作以幂等键去重
 * （见 {@code StubOperations.SubmitTransfer}）——流程本身会被完整跑第二遍，
 * 幂等的责任在写操作上。这正是本模块要传达给行内的写法。
 */
class WorkflowAgentContractTest extends DomainAgentContract {

    @Override
    protected DomainAgent agent() {
        List<FlowSpec> specs = new FlowSpecLoader().load(Path.of("src/test/resources/flows"));
        StubOperations.CallLog log = new StubOperations.CallLog();
        return new WorkflowDomainAgent(
                Map.of(specs.get(0).capabilityId(), specs.get(0)),
                new FlowCompiler(StubOperations.all(log, new BigDecimal("50000"), new BigDecimal("10000"))));
    }

    @Override
    protected String capabilityId() {
        return "cap.transfer";
    }

    @Override
    protected Map<String, Object> validParameters() {
        return Map.of("payee", "张三", "amount", "1000");
    }
}
