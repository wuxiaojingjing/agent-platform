package com.huawei.finance.product.mobilebanking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.product.mobilebanking.ChatService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatStreamControllerTest {

    @Test
    void streamsCardsInPlanOrderBeforeCompletingTheTurn() throws Exception {
        ResponsePlan plan = ResponsePlan.builder()
                .traceId("trace-stream")
                .taskId("task-stream")
                .slots(Map.of("resultCards", List.of(
                        Map.of("title", "余额结果"), Map.of("title", "产品结果"))))
                .cardComponents(List.of("TASK_PROGRESS", "RESULT_SUMMARY", "RISK_NOTICE"))
                .build();
        ChatResponseDto response = new ChatResponseDto("trace-stream", "请确认转账", null, plan,
                "task-stream", "transfer-confirm", false, List.of(), List.of());
        ChatService service = serviceReturning(response);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ChatController(service, new SimpleMeterRegistry())).build();
        MvcResult pending = mvc.perform(post("/api/v1/chat/stream")
                        .header(TenantHeaders.HEADER_USER_ID, "user-1")
                        .header(TenantHeaders.HEADER_SPACE_ID, "space-1")
                        .header(TenantHeaders.HEADER_CHANNEL_ID, "MOBILE_BANK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"session-1","query":"先查余额，不足就别转",
                                 "channel":"MOBILE_BANK"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        pending.getAsyncResult(2_000);
        String stream = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int started = stream.indexOf("event:TURN_STARTED");
        int progress = stream.indexOf("\"component\":\"TASK_PROGRESS\"");
        int firstResult = stream.indexOf("\"component\":\"RESULT_SUMMARY\",\"itemIndex\":0");
        int secondResult = stream.indexOf("\"component\":\"RESULT_SUMMARY\",\"itemIndex\":1");
        int risk = stream.indexOf("\"component\":\"RISK_NOTICE\"");
        int completed = stream.indexOf("event:TURN_COMPLETED");
        assertThat(started).isGreaterThanOrEqualTo(0);
        assertThat(progress).isGreaterThan(started);
        assertThat(firstResult).isGreaterThan(progress);
        assertThat(secondResult).isGreaterThan(firstResult);
        assertThat(risk).isGreaterThan(secondResult);
        assertThat(completed).isGreaterThan(risk);
    }

    @Test
    void rejectsInvalidRequestsBeforeOpeningAStream() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ChatController(serviceReturning(null), new SimpleMeterRegistry())).build();

        mvc.perform(post("/api/v1/chat/stream")
                        .header(TenantHeaders.HEADER_USER_ID, "user-1")
                        .header(TenantHeaders.HEADER_SPACE_ID, "space-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"\",\"query\":\"查余额\"}"))
                .andExpect(status().isBadRequest());
    }

    private static ChatService serviceReturning(ChatResponseDto response) {
        return new ChatService(null) {
            @Override
            public ChatResponseDto chat(ChatRequestDto request, TenantHeaders tenant) {
                return response;
            }
        };
    }
}
