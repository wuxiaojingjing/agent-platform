package com.huawei.finance.response;

import com.huawei.finance.contracts.model.Enums;
import java.util.List;
import java.util.Map;

/** SPI for model-assisted visible text. It cannot return actions or state transitions. */
public interface ResponseTextModel {

    Result realize(Request request);

    static ResponseTextModel unavailable() {
        return request -> Result.unavailable("response-model-unavailable");
    }

    record Request(
            Enums.RenderMode mode,
            String model,
            String systemPrompt,
            String promptVersion,
            double temperature,
            int maxTokens,
            String baseText,
            Map<String, String> approvedTemplates,
            List<Map<String, Object>> conversationHistory,
            String userQuery,
            Map<String, Object> committedFacts,
            List<String> riskNoticeCodes,
            Enums.ResponsePhase phase) {
        public Request {
            approvedTemplates = approvedTemplates == null ? Map.of() : Map.copyOf(approvedTemplates);
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
            committedFacts = committedFacts == null ? Map.of() : Map.copyOf(committedFacts);
            riskNoticeCodes = riskNoticeCodes == null ? List.of() : List.copyOf(riskNoticeCodes);
        }
    }

    record Result(boolean available, String text, String templateKey,
                  String modelVersion, String reason) {
        public static Result text(String text, String modelVersion) {
            return new Result(true, text, null, modelVersion, null);
        }
        public static Result template(String templateKey, String modelVersion) {
            return new Result(true, null, templateKey, modelVersion, null);
        }
        public static Result unavailable(String reason) {
            return new Result(false, null, null, null, reason);
        }
    }
}
