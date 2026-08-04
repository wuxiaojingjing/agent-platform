package com.huawei.finance.sample.oj;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装在 OpenJiuwen Agent Server 进程里的行内 {@link AgentHandler}。
 *
 * <p>实施架构 §6.3 建议面客域用行内 Handler 而不是自带的 {@code JiuwenCoreAgentHandler}，
 * 理由在本类的三件事上：
 *
 * <ol>
 *   <li><b>入参必须验信封</b>。默认 Handler 会把请求当自由对话交给大模型；
 *       而到了这一层的请求是要动真钱的，必须是结构化任务，不是一段话。</li>
 *   <li><b>出参必须是结构化 {@link TaskResult}</b>，且剥掉一切可面客的自由文本。
 *       面客回复由 {@code response-engine} 依模板生成（WP8：禁止 Agent 面客直出）。</li>
 *   <li><b>执行体无状态</b>。任务真值在中控（实施架构 §8.4），这里不持有、不补偿、不重试，
 *       失败就如实回报失败分类。</li>
 * </ol>
 *
 * <p>本类自己不实现任何业务，它把解出来的任务转交给进程内的 {@link DomainAgent}。
 * 这样行内真实的领域实现只需要面对 {@link DomainAgent} 这一个接口，
 * 既不必知道 OJ 的存在，也能直接跑 {@code agent-tck} 的契约用例。
 */
public class OpenJiuwenAgentHandler implements AgentHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenJiuwenAgentHandler.class);

    private final List<DomainAgent> agents;
    private final OjQueryCodec codec;

    public OpenJiuwenAgentHandler(List<DomainAgent> agents, OjQueryCodec codec) {
        this.agents = List.copyOf(agents);
        this.codec = codec;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        UnifiedTask task;
        try {
            task = codec.decodeTask(request);
        } catch (OjCodecException e) {
            // 解不出信封时连 taskId 都没有，没法编成 TaskResult 回给中控。
            // 抛出去让 Agent Server 按错误响应处理 —— 中控那边会因为响应缺信封而整单失败，
            // 这正是要的结果
            log.warn("拒绝一次非结构化请求：{}", e.getMessage());
            throw e;
        }

        DomainAgent agent = agents.stream()
                .filter(a -> a.supports(task.capabilityId()))
                .findFirst()
                .orElse(null);
        if (agent == null) {
            log.error("本 Agent Server 没有实现承接能力 {} 的 DomainAgent", task.capabilityId());
            return codec.encodeResult(
                    OjQueryCodec.failure(task, Enums.FailureClass.FATAL, "NO_AGENT_FOR_CAPABILITY"),
                    request.getConversationId());
        }

        TaskResult result;
        try {
            result = agent.execute(task);
        } catch (RuntimeException e) {
            // 领域实现抛异常时，这里兜住并归成 RETRYABLE 而不是让异常穿到 HTTP 层：
            // 穿上去中控只会看到一个 5xx，丢掉「这次失败能不能重试」这个信息，
            // 而那正是中控决定补偿动作的依据
            log.error("领域 Agent 执行 {} 抛异常", task.capabilityId(), e);
            result = OjQueryCodec.failure(task, Enums.FailureClass.RETRYABLE, "AGENT_EXCEPTION");
        }
        if (result == null) {
            log.error("领域 Agent 对 {} 返回了 null", task.capabilityId());
            result = OjQueryCodec.failure(task, Enums.FailureClass.FATAL, "AGENT_RETURNED_NULL");
        }
        return codec.encodeResult(result, request.getConversationId());
    }

    /**
     * 流式不支持，明确拒绝。
     *
     * <p>不是「暂未实现」。流式的用途是把自然语言逐字吐给用户，而这条链路上的回复由
     * {@code response-engine} 依模板生成——留一个能流式输出的口子，就等于留了一条
     * 绕过模板与护栏直接面客的路径（WP8）。
     */
    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR,
                "STREAMING_NOT_SUPPORTED: 面客回复由中控依模板生成，本 Handler 不做自由文本直出"));
        observer.onError(new UnsupportedOperationException(
                "手机银行助手领域 Agent 不支持流式：回复生成在 OJ 之外"));
    }
}
