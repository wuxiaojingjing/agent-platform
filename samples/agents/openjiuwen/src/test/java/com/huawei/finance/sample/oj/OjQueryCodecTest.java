package com.huawei.finance.sample.oj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.GuardrailCheck;
import com.huawei.finance.contracts.model.RiskLevel;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OjQueryCodecTest {

    private final OjQueryCodec codec = new OjQueryCodec();

    @Test
    @DisplayName("任务经 QueryRequest→ServeRequest 往返后字段不丢")
    void taskSurvivesRoundTrip() {
        UnifiedTask task = transferTask();

        // fromQueryRequest 是 OJ 服务端入口真正走的那一步，所以这里必须经它，
        // 而不是自己 new 一个 ServeRequest 把 messages 塞进去 —— 那样测不到它会不会丢字段
        UnifiedTask decoded = codec.decodeTask(ServeRequest.fromQueryRequest(codec.encode(task)));

        assertThat(decoded.taskId()).isEqualTo(task.taskId());
        assertThat(decoded.capabilityId()).isEqualTo(task.capabilityId());
        assertThat(decoded.idempotencyKey()).isEqualTo(task.idempotencyKey());
        assertThat(decoded.riskLevel()).isEqualTo(RiskLevel.R2);
        assertThat(decoded.parameters()).isEqualTo(task.parameters());
        assertThat(decoded.guardrailCheck().isPassed())
                .as("护栏结论必须过得去。丢了它，领域方就无从判断这次执行有没有凭据")
                .isTrue();
    }

    @Test
    @DisplayName("请求恒为非流式，conversationId 用 taskId")
    void requestIsNonStreamingAndScopedToTask() {
        QueryRequest request = codec.encode(transferTask());

        assertThat(request.isStream())
                .as("流式意味着自由文本直出，这条链路上不允许")
                .isFalse();
        assertThat(request.getConversationId())
                .as("按任务隔离，避免 Agent Server 跨任务累积对话记忆")
                .startsWith("task-");
    }

    @Test
    @DisplayName("content 里不放用户原句")
    void contentCarriesNoUserUtterance() {
        UnifiedTask task = transferTask();
        Map<String, Object> message = codec.encode(task).getMessages().get(0);

        assertThat((String) message.get("content"))
                .as("这个字段一旦被通用 Handler 当 prompt 用，放什么就等于问了模型什么")
                .doesNotContain("张三")
                .isEqualTo(OjQueryCodec.CONTENT_PLACEHOLDER);
        assertThat(codec.decodeTask(ServeRequest.fromQueryRequest(codec.encode(task))).goal())
                .as("用户原句仍要送到领域方，但走 goal 这条结构化通道")
                .isEqualTo(task.goal());
    }

    @Test
    @DisplayName("结果往返后失败分类与幂等键不丢")
    void resultSurvivesRoundTrip() {
        UnifiedTask task = transferTask();
        TaskResult failed = OjQueryCodec.failure(task, Enums.FailureClass.RETRYABLE, "DOWNSTREAM_TIMEOUT");

        TaskResult decoded = codec.decodeResult(codec.encodeResult(failed, task.taskId()));

        assertThat(decoded.status()).isEqualTo(Enums.TaskStatus.FAILED);
        assertThat(decoded.failureClass())
                .as("中控据它决定重试还是补偿，丢了就只能按最坏情况处理")
                .isEqualTo(Enums.FailureClass.RETRYABLE);
        assertThat(decoded.idempotencyKey()).isEqualTo(task.idempotencyKey());
        assertThat(decoded.resultPayload()).containsEntry("error", "DOWNSTREAM_TIMEOUT");
    }

    /**
     * 这一组是本类存在的主要理由：解码遇到任何非本契约的东西都必须拒绝。
     *
     * <p>要防的不是畸形数据——畸形数据本来就会解析失败。要防的是**看起来完全正常**的
     * 自然语言响应：把中控错配到 OJ 自带的通用 Handler 上，拿回来的会是
     * 「已为您转账 1000 元」这类句子，而它对应的转账从未发生。
     */
    @Test
    @DisplayName("拒绝自然语言响应：模型编出来的成功比失败更危险")
    void rejectsNaturalLanguageResponse() {
        QueryResponse llmish = new QueryResponse("已为您向张三转账 1000 元，请查收。", "task-1");

        assertThatThrownBy(() -> codec.decodeResult(llmish))
                .isInstanceOf(OjCodecException.class)
                .hasMessageContaining("不是结构化对象");
    }

    @Test
    @DisplayName("拒绝没有信封的结构化响应")
    void rejectsStructuredResponseWithoutEnvelope() {
        // 结构对了但没信封：比如上游换了实现、或中间层重新包装过响应。
        // 里面那个 status=SUCCESS 完全可能是别的东西的成功
        QueryResponse noEnvelope = new QueryResponse(Map.of("status", "SUCCESS"), "task-1");

        assertThatThrownBy(() -> codec.decodeResult(noEnvelope))
                .isInstanceOf(OjCodecException.class)
                .hasMessageContaining("版本不符");
    }

    @Test
    @DisplayName("版本不符按失败处理，不做兼容性猜测")
    void rejectsVersionMismatch() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(OjQueryCodec.VERSION_KEY, "agent-platform-oj-envelope-v0");
        body.put(OjQueryCodec.RESULT_ENVELOPE_KEY, Map.of("taskId", "task-1", "status", "SUCCESS"));

        assertThatThrownBy(() -> codec.decodeResult(new QueryResponse(body, "task-1")))
                .isInstanceOf(OjCodecException.class)
                .hasMessageContaining("版本不符");
    }

    @Test
    @DisplayName("Handler 侧拒绝普通对话请求")
    void handlerSideRejectsPlainChat() {
        QueryRequest chat = new QueryRequest();
        chat.setConversationId("conv-1");
        chat.setMessages(List.of(Map.of("role", "user", "content", "帮我转 1000 给张三")));

        assertThatThrownBy(() -> codec.decodeTask(ServeRequest.fromQueryRequest(chat)))
                .isInstanceOf(OjCodecException.class)
                .hasMessageContaining(OjQueryCodec.ENVELOPE_KEY);
    }

    @Test
    @DisplayName("Handler 侧拒绝空请求")
    void handlerSideRejectsEmptyRequest() {
        assertThatThrownBy(() -> codec.decodeTask(new ServeRequest()))
                .isInstanceOf(OjCodecException.class);
    }

    static UnifiedTask transferTask() {
        return new UnifiedTask(
                "task-" + System.nanoTime(),
                "trace-1",
                Enums.TaskSource.FAST_PATH,
                "帮我转 1000 给张三",
                "cap.transfer",
                Map.of("payee", "张三", "amount", "1000"),
                RiskLevel.R2,
                Map.of("confirmed", true),
                GuardrailCheck.passed(),
                "idem-1",
                List.of("ctx-1"),
                null);
    }
}
