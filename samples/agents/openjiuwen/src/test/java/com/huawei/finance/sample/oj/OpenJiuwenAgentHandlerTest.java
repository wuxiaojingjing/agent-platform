package com.huawei.finance.sample.oj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.port.DomainAgent;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenJiuwenAgentHandlerTest {

    private final OjQueryCodec codec = new OjQueryCodec();

    @Test
    @DisplayName("正常任务转交给承接该能力的 DomainAgent")
    void delegatesToMatchingAgent() {
        OpenJiuwenAgentHandler handler = new OpenJiuwenAgentHandler(List.of(new StubTransferAgent()), codec);
        UnifiedTask task = OjQueryCodecTest.transferTask();

        TaskResult result = codec.decodeResult(handler.query(serveRequest(task)));

        assertThat(result.success()).isTrue();
        assertThat(result.taskId()).isEqualTo(task.taskId());
        assertThat(result.resultPayload()).containsKey("serialNo");
    }

    @Test
    @DisplayName("没有实现承接该能力时，回 FATAL 而不是让请求悬着")
    void reportsFatalWhenNoAgentSupportsCapability() {
        OpenJiuwenAgentHandler handler = new OpenJiuwenAgentHandler(List.of(), codec);
        UnifiedTask task = OjQueryCodecTest.transferTask();

        TaskResult result = codec.decodeResult(handler.query(serveRequest(task)));

        assertThat(result.success()).isFalse();
        assertThat(result.failureClass())
                .as("重试也不会让这个进程长出实现来，判 FATAL 才不会让中控白等几轮")
                .isEqualTo(Enums.FailureClass.FATAL);
        assertThat(result.resultPayload()).containsEntry("error", "NO_AGENT_FOR_CAPABILITY");
    }

    @Test
    @DisplayName("领域实现抛异常时归成 RETRYABLE，不让异常穿到 HTTP 层")
    void wrapsAgentExceptionAsRetryable() {
        OpenJiuwenAgentHandler handler = new OpenJiuwenAgentHandler(List.of(throwingAgent()), codec);

        TaskResult result = codec.decodeResult(handler.query(serveRequest(OjQueryCodecTest.transferTask())));

        assertThat(result.failureClass())
                .as("穿到 HTTP 层就只剩一个 5xx，中控丢掉的是「能不能重试」这条信息")
                .isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(result.resultPayload()).containsEntry("error", "AGENT_EXCEPTION");
    }

    @Test
    @DisplayName("领域实现返回 null 时不产生空响应")
    void handlesNullResult() {
        OpenJiuwenAgentHandler handler = new OpenJiuwenAgentHandler(List.of(nullReturningAgent()), codec);

        TaskResult result = codec.decodeResult(handler.query(serveRequest(OjQueryCodecTest.transferTask())));

        assertThat(result.resultPayload()).containsEntry("error", "AGENT_RETURNED_NULL");
    }

    @Test
    @DisplayName("普通对话请求打进来时整单拒绝")
    void rejectsPlainChatRequest() {
        OpenJiuwenAgentHandler handler = new OpenJiuwenAgentHandler(List.of(new StubTransferAgent()), codec);
        ServeRequest chat = new ServeRequest();
        chat.setConversationId("conv-1");
        chat.setMessages(List.of(Map.of("role", "user", "content", "帮我转 1000 给张三")));

        // 这里刻意抛而不是回一个 TaskResult：解不出信封就连 taskId 都没有，
        // 编不出一个能对上账的结果。让它成为错误响应，中控那侧会因为响应缺信封而整单失败
        assertThatThrownBy(() -> handler.query(chat)).isInstanceOf(OjCodecException.class);
    }

    @Test
    @DisplayName("流式被明确拒绝，不是静默降级")
    void refusesStreaming() {
        OpenJiuwenAgentHandler handler = new OpenJiuwenAgentHandler(List.of(new StubTransferAgent()), codec);
        List<QueryChunk> chunks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();

        handler.streamQuery(serveRequest(OjQueryCodecTest.transferTask()), observer(chunks, errors));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getType()).isEqualTo(QueryChunk.TYPE_ERROR);
        assertThat(errors)
                .as("只发 chunk 不 onError，调用方会一直等着流结束")
                .hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(UnsupportedOperationException.class);
    }

    private ServeRequest serveRequest(UnifiedTask task) {
        return ServeRequest.fromQueryRequest(codec.encode(task));
    }

    private static DomainAgent throwingAgent() {
        return new DomainAgent() {
            @Override
            public boolean supports(String capabilityId) {
                return true;
            }

            @Override
            public TaskResult execute(UnifiedTask task) {
                throw new IllegalStateException("下游连接池耗尽");
            }
        };
    }

    private static DomainAgent nullReturningAgent() {
        return new DomainAgent() {
            @Override
            public boolean supports(String capabilityId) {
                return true;
            }

            @Override
            public TaskResult execute(UnifiedTask task) {
                return null;
            }
        };
    }

    private static QueryStreamObserver observer(List<QueryChunk> chunks, List<Throwable> errors) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }

            @Override
            public void onComplete() {
                // 流式不支持，不该走到这里
            }
        };
    }
}
