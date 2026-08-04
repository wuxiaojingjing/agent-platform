package com.huawei.finance.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * OpenAI 兼容 chat SSE 解析（FP-63）。
 *
 * <p>只关心两件事：把 content delta 拼成完整答复，以及在流里钉下首帧 / 首 token 时刻。
 * 熔断、重试、HTTP 不进这里——那些属于调用骨架。
 */
final class SseChatParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SseChatParser() {
    }

    static ChatStreamTimings parse(Reader reader, long startedNanos) throws IOException {
        BufferedReader in = reader instanceof BufferedReader br ? br : new BufferedReader(reader);
        StringBuilder content = new StringBuilder();
        long firstFrameMs = -1;
        long firstTokenMs = -1;
        int completionTokens = 0;

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if (firstFrameMs < 0) {
                firstFrameMs = elapsedMs(startedNanos);
            }
            if ("[DONE]".equals(payload)) {
                break;
            }
            JsonNode root = MAPPER.readTree(payload);
            int usageTokens = root.path("usage").path("completion_tokens").asInt(0);
            if (usageTokens > 0) {
                completionTokens = usageTokens;
            }
            JsonNode delta = root.path("choices").path(0).path("delta");
            String piece = delta.path("content").asText(null);
            if (piece != null && !piece.isEmpty()) {
                if (firstTokenMs < 0) {
                    firstTokenMs = elapsedMs(startedNanos);
                }
                content.append(piece);
            }
            // 部分供应商在非流式字段里回整段 content（容错，不改变计时语义）
            if (content.isEmpty()) {
                String full = root.path("choices").path(0).path("message").path("content").asText(null);
                if (full != null && !full.isEmpty()) {
                    if (firstTokenMs < 0) {
                        firstTokenMs = elapsedMs(startedNanos);
                    }
                    content.append(full);
                }
            }
        }

        return new ChatStreamTimings(
                content.toString(),
                firstFrameMs,
                firstTokenMs,
                completionTokens,
                elapsedMs(startedNanos));
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
