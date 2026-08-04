package com.huawei.finance.sample.oj;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.contracts.validation.ContractJson;
import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link UnifiedTask} / {@link TaskResult} 与 OpenJiuwen {@code POST /v1/query} 契约之间的编解码。
 *
 * <h2>为什么需要适配，而不是直接映射</h2>
 *
 * <p>OJ 的 query 契约是**对话式**的：{@code messages} / {@code message} 是自由文本，
 * 加上 {@code conversationId} / {@code userId} 这类会话标识。而本系统交给领域方的是
 * {@link UnifiedTask}——结构化、带风险等级、带护栏结论、带幂等键。两者不是同一种东西，
 * 中间必须有一层显式的信封，不能指望字段名对上。
 *
 * <h2>载荷为什么塞在 messages 里</h2>
 *
 * <p>看起来该用 {@link ServeRequest#getMetadata()}——那正是给结构化附加信息准备的字段。
 * 但它**客户端够不到**：{@link QueryRequest} 上没有这个字段，而
 * {@link ServeRequest#fromQueryRequest} 逐个拷贝 conversationId / messages / userId /
 * spaceId / tenantId / stream，独独没有 metadata。也就是说 metadata 只能由服务端进程自己填。
 * 这是 OJ 0.1.0 契约面的一个缺口，等上游把 metadata 放到 {@code QueryRequest} 上之后，
 * 这里应当迁过去——届时 {@link #ENVELOPE_VERSION} 要升。
 *
 * <p>退而用 {@code messages} 是因为它是**两个类型上都有**的唯一结构化通道
 * （{@code List<Map<String, Object>>}，Map 里的键不会被 Jackson 丢掉，
 * 而 {@code QueryRequest.normalizeMessages()} 只在 messages 为空时才用 message 合成，
 * 不会剥掉已有条目的额外键）。
 *
 * <h2>为什么解码必须验信封</h2>
 *
 * <p>这是本类最要紧的一条。若把中控指向 OJ 自带的默认 Handler，它会把
 * {@link ServeRequest#lastUserQuery()} 喂给大模型，模型十分可能返回一句
 * 「已为您转账 1000 元」这样**看起来完全正常**的自然语言。若解码只是「取 result 当结果」，
 * 中控就会把一笔从未发生的转账记为成功——没有异常、没有报错，账实不符要等对账才发现。
 *
 * <p>所以解码要求响应里带着本编解码器写下的信封标记与版本，缺一律拒绝
 * （{@link OjCodecException}）。宁可整单失败，也不能把模型的话当成执行结果。
 */
public final class OjQueryCodec {

    /**
     * 信封键名。挂在 message 条目上作为 {@code role}/{@code content} 的兄弟键。
     *
     * <p>取这个前缀是为了在 OJ 侧的日志与调试面板里一眼看出这不是普通对话字段。
     */
    public static final String ENVELOPE_KEY = "agentUnifiedTask";

    /** 响应信封键名。 */
    public static final String RESULT_ENVELOPE_KEY = "agentTaskResult";

    /** 信封版本。载荷结构变化（含日后迁到 metadata）时必须升。 */
    public static final String ENVELOPE_VERSION = "agent-platform-oj-envelope-v1";

    public static final String VERSION_KEY = "agentEnvelopeVersion";

    /**
     * 放在 {@code content} 里的占位文本。
     *
     * <p>刻意**不放用户原句**。若这个请求错发给了默认 Handler，{@code content} 就是喂给
     * 大模型的 prompt；放用户原句等于把一次交易执行请求变成一次自由对话，
     * 而模型的回答会长得很像执行结果。放一句明确的「这不是给你读的」，
     * 出问题时在模型侧日志里也看得出是谁发错了。
     *
     * <p>用户原句仍然会下发给领域方——它在 {@link UnifiedTask#goal()} 里，
     * 随结构化载荷走，不经过这个字段（R2 需要原句做自己那道风险复核）。
     */
    static final String CONTENT_PLACEHOLDER =
            "[agent-platform] structured task envelope; not a natural-language prompt";

    private final ObjectMapper mapper;

    public OjQueryCodec() {
        this(ContractJson.mapper());
    }

    public OjQueryCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 中控侧：把任务编成一次 query 请求。 */
    public QueryRequest encode(UnifiedTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", CONTENT_PLACEHOLDER);
        message.put(VERSION_KEY, ENVELOPE_VERSION);
        message.put(ENVELOPE_KEY, mapper.convertValue(task, Map.class));

        QueryRequest request = new QueryRequest();
        request.setMessages(List.of(message));
        // conversationId 用 taskId 而不是会话 id：交易类执行要的是确定性，
        // 不要 Agent Server 跨任务累积对话记忆。同一个会话里连转两笔，
        // 第二笔不该看见第一笔的上下文
        request.setConversationId(task.taskId());
        // userId 留空：UnifiedTask 里没有用户标识。这不是漏了——契约上领域方不依据中控
        // 传来的身份做鉴权（那等于把鉴权信任链交给上游），身份由领域侧自己那条通道确认。
        // 若日后要传，得先在 UnifiedTask 上加字段并升信封版本，不能悄悄借用这个字段
        // 恒为 false。面客回复由 response-engine 生成，Agent 一律不得面客直出（实施架构 WP8），
        // 流式自然语言在这条链路上没有消费者，开着只是把风险面打开
        request.setStream(false);
        return request;
    }

    /** Handler 侧：从请求里取回任务。 */
    public UnifiedTask decodeTask(ServeRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new OjCodecException("请求里没有 messages，不是本编解码器发出的调用");
        }
        Map<String, Object> envelope = request.getMessages().stream()
                .filter(m -> m != null && m.containsKey(ENVELOPE_KEY))
                .findFirst()
                .orElseThrow(() -> new OjCodecException(
                        "messages 里没有 " + ENVELOPE_KEY + " 信封。"
                                + "这多半是一次普通对话请求打到了手机银行助手的 Handler 上"));
        requireVersion(envelope);
        Object payload = envelope.get(ENVELOPE_KEY);
        try {
            return mapper.convertValue(payload, UnifiedTask.class);
        } catch (IllegalArgumentException e) {
            throw new OjCodecException("信封里的 UnifiedTask 解不出来：" + e.getMessage(), e);
        }
    }

    /** Handler 侧：把执行结果编成响应。 */
    public QueryResponse encodeResult(TaskResult result, String conversationId) {
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(VERSION_KEY, ENVELOPE_VERSION);
        body.put(RESULT_ENVELOPE_KEY, mapper.convertValue(result, Map.class));
        return new QueryResponse(body, conversationId);
    }

    /**
     * 中控侧：从响应里取回结果。信封不对就抛，不做任何猜测性解读。
     *
     * @throws OjCodecException 响应缺信封或版本不符
     */
    public TaskResult decodeResult(QueryResponse response) {
        if (response == null || response.getResult() == null) {
            throw new OjCodecException("响应里没有 result");
        }
        if (!(response.getResult() instanceof Map<?, ?> raw)) {
            throw new OjCodecException(
                    "响应的 result 不是结构化对象而是 " + response.getResult().getClass().getName()
                            + "。若这是一段自然语言，说明请求打到了通用 Handler 上，"
                            + "那段文字可能是模型编出来的执行结果，绝不能当真");
        }
        Map<String, Object> body = castKeys(raw);
        requireVersion(body);
        Object payload = body.get(RESULT_ENVELOPE_KEY);
        if (payload == null) {
            throw new OjCodecException("响应里没有 " + RESULT_ENVELOPE_KEY + " 信封");
        }
        try {
            TaskResult result = mapper.convertValue(payload, TaskResult.class);
            if (result.status() == null) {
                throw new OjCodecException("解出的 TaskResult 没有 status");
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new OjCodecException("信封里的 TaskResult 解不出来：" + e.getMessage(), e);
        }
    }

    /**
     * 领域侧执行失败时，给中控一个结构完整、失败分类明确的结果。
     *
     * <p>{@code failureClass} 不是装饰：中控据它决定是重试、补偿还是直接告知用户
     * （{@link Enums.FailureClass#RETRYABLE} 与 {@link Enums.FailureClass#FATAL}
     * 对一笔转账意味着完全不同的后续动作）。所以这里要求显式传入，不给默认值。
     *
     * <p>幂等键要原样带回。中控是按幂等键认这次执行的，回一个空的等于让它对不上账。
     */
    public static TaskResult failure(UnifiedTask task, Enums.FailureClass failureClass, String code) {
        return new TaskResult(task == null ? null : task.taskId(),
                Enums.TaskStatus.FAILED, failureClass, Map.of("error", code),
                task == null ? null : task.idempotencyKey(),
                task == null ? null : task.guardrailCheck());
    }

    private static void requireVersion(Map<String, Object> envelope) {
        Object version = envelope.get(VERSION_KEY);
        if (!ENVELOPE_VERSION.equals(version)) {
            throw new OjCodecException("信封版本不符：期望 " + ENVELOPE_VERSION + "，实际 " + version
                    + "。版本不一致时字段语义可能已变，按失败处理而不是尽力解析");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castKeys(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
