package com.huawei.finance.fastpath;

import com.huawei.finance.contracts.model.RecallResult;
import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.intent.extension.CandidateSet;
import com.huawei.finance.intent.extension.ExtensionFailurePolicy;
import com.huawei.finance.intent.extension.IntentExtensionException;
import com.huawei.finance.intent.extension.IntentInput;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 执行有序候选扩展，并在每一步后校验不可穿透的候选安全边界。 */
final class CandidatePostProcessorChain {

    private static final Logger log = LoggerFactory.getLogger(CandidatePostProcessorChain.class);

    private final List<CandidatePostProcessor> processors;

    CandidatePostProcessorChain(List<CandidatePostProcessor> processors) {
        this.processors = processors == null ? List.of() : List.copyOf(processors);
    }

    CandidateSet process(IntentInput input, CandidateSet platformDefault) {
        CandidateSet current = platformDefault;
        for (CandidatePostProcessor processor : processors) {
            try {
                CandidateSet processed = Objects.requireNonNull(
                        processor.process(input, current),
                        "CandidatePostProcessor 返回 null");
                validate(platformDefault, processed, processor.extensionId());
                current = processed;
            } catch (RuntimeException failure) {
                ExtensionFailurePolicy policy = Objects.requireNonNullElse(
                        processor.failurePolicy(), ExtensionFailurePolicy.FAIL_CLOSED);
                if (policy == ExtensionFailurePolicy.FAIL_CLOSED) {
                    throw new IntentExtensionException(
                            "候选扩展失败: " + processor.extensionId(), failure);
                }
                if (policy == ExtensionFailurePolicy.FALLBACK_DEFAULT) {
                    current = platformDefault;
                }
                log.warn("候选扩展已按策略收口 extension={} policy={} cause={}",
                        processor.extensionId(), policy, failure.toString());
            }
        }
        return current;
    }

    private static void validate(CandidateSet platformDefault, CandidateSet processed,
                                 String extensionId) {
        RecallResult baseline = platformDefault.recall();
        RecallResult result = processed.recall();
        if (!Objects.equals(result.domainRouting(), baseline.domainRouting())
                || !Objects.equals(result.degradedChannels(), baseline.degradedChannels())) {
            throw violation(extensionId, "不得修改领域路由或通道降级事实");
        }

        Map<String, RecallResult.Candidate> allowed = new LinkedHashMap<>();
        for (RecallResult.Candidate candidate : baseline.candidates()) {
            allowed.put(candidate.candidateId(), candidate);
        }
        for (RecallResult.Candidate candidate : result.candidates()) {
            RecallResult.Candidate original = allowed.get(candidate.candidateId());
            if (original == null) {
                throw violation(extensionId, "不得新增未召回候选: " + candidate.candidateId());
            }
            if (!original.equals(candidate)) {
                throw violation(extensionId,
                        "不得修改候选领域、槽位、风险、证据或来源: " + candidate.candidateId());
            }
        }
    }

    private static IllegalArgumentException violation(String extensionId, String reason) {
        return new IllegalArgumentException("扩展 " + extensionId + " 违反候选契约: " + reason);
    }
}
