package com.huawei.finance.product.mobilebanking.api;

import com.huawei.finance.product.mobilebanking.ChatService;
import com.huawei.finance.obs.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

/** 会话入口。 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public ChatController(ChatService chatService, MeterRegistry meterRegistry) {
        this(chatService, meterRegistry, null);
    }

    @Autowired
    public ChatController(ChatService chatService, MeterRegistry meterRegistry, Tracer tracer) {
        this.chatService = chatService;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /**
     * @param userId  {@code X-User-ID}，由渠道网关在鉴权之后注入。**必填**，见 {@link TenantHeaders}
     * @param spaceId {@code X-Space-ID}，租户/空间。**必填**，它参与出口缓存键
     * @param channel {@code X-Channel-ID}，可选，缺失时回落请求体的 channel
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(
            @RequestHeader(value = TenantHeaders.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = TenantHeaders.HEADER_SPACE_ID, required = false) String spaceId,
            @RequestHeader(value = TenantHeaders.HEADER_CHANNEL_ID, required = false) String channel,
            @RequestBody ChatRequestDto request) {

        if (request.sessionId() == null || request.sessionId().isBlank()) {
            // 没有会话就没有任务归属，续轮、澄清、确认全都无从谈起
            return ResponseEntity.badRequest().build();
        }
        if ((request.query() == null || request.query().isBlank()) && request.action() == null) {
            return ResponseEntity.badRequest().build();
        }

        // 头用 required=false 接进来再自己判，是为了让缺头与冲突各有一个可归因的拒绝原因。
        // 交给 Spring 用 required=true 拦，三种情况会合并成同一个 400，
        // 而「谁家渠道没注入头」与「有人在改 body」是两件要分开处置的事
        TenantHeaders.Resolution resolved = TenantHeaders.resolve(
                userId, spaceId, channel, request.userId(), request.channel());
        if (resolved.rejected()) {
            log.warn("租户头校验未通过 session={} 原因={}", request.sessionId(), resolved.rejection());
            meterRegistry.counter(AgentMetrics.TENANT_HEADER_REJECTED,
                    AgentMetrics.TAG_REASON, resolved.rejection().name()).increment();
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(chatService.chat(request, resolved.headers()));
    }

    /**
     * Streams presentation milestones in card order. Runtime and task state still have one
     * authoritative write path; this endpoint only projects the final audited response gradually.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestHeader(value = TenantHeaders.HEADER_USER_ID, required = false) String userId,
            @RequestHeader(value = TenantHeaders.HEADER_SPACE_ID, required = false) String spaceId,
            @RequestHeader(value = TenantHeaders.HEADER_CHANNEL_ID, required = false) String channel,
            @RequestBody ChatRequestDto request) {
        if (request.sessionId() == null || request.sessionId().isBlank()
                || ((request.query() == null || request.query().isBlank()) && request.action() == null)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_CHAT_REQUEST");
        }
        TenantHeaders.Resolution resolved = TenantHeaders.resolve(
                userId, spaceId, channel, request.userId(), request.channel());
        if (resolved.rejected()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, resolved.rejection().name());
        }

        SseEmitter emitter = new SseEmitter(60_000L);
        Span entrySpan = tracer == null ? null : tracer.currentSpan();
        Thread.ofVirtual().name("chat-stream-" + request.sessionId()).start(() -> {
            Tracer.SpanInScope spanScope = entrySpan == null ? null : tracer.withSpan(entrySpan);
            try (spanScope) {
                send(emitter, ChatStreamEvent.started());
                ChatResponseDto response = chatService.chat(request, resolved.headers());
                long sequence = 1;
                if (response.plan() != null && response.plan().cardComponents() != null) {
                    for (String component : response.plan().cardComponents()) {
                        int itemCount = projectionItemCount(response, component);
                        for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
                            send(emitter, new ChatStreamEvent(sequence++, "CARD_AVAILABLE",
                                    component, itemIndex, itemCount, response, null));
                        }
                    }
                }
                send(emitter, new ChatStreamEvent(sequence, "TURN_COMPLETED",
                        null, null, null, response, null));
                emitter.complete();
            } catch (Exception failure) {
                try {
                    send(emitter, new ChatStreamEvent(Long.MAX_VALUE, "TURN_FAILED",
                            null, null, null, null, "本轮处理失败，请重试或联系人工服务"));
                    emitter.complete();
                } catch (Exception sendFailure) {
                    emitter.completeWithError(failure);
                }
            }
        });
        return emitter;
    }

    private static int projectionItemCount(ChatResponseDto response, String component) {
        if (!"RESULT_SUMMARY".equals(component) || response.plan() == null) {
            return 1;
        }
        Object resultCards = response.plan().slots().get("resultCards");
        if (resultCards instanceof java.util.List<?> cards && !cards.isEmpty()) {
            return cards.size();
        }
        return 1;
    }

    private static void send(SseEmitter emitter, ChatStreamEvent event) throws IOException {
        emitter.send(SseEmitter.event().id(String.valueOf(event.sequence()))
                .name(event.type()).data(event));
    }
}
