package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.intent.extension.CandidatePostProcessor;
import com.huawei.finance.intent.extension.CandidateSet;
import com.huawei.finance.intent.extension.IntentInput;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 业务 {@link CandidatePostProcessor} 的最低契约测试。
 *
 * <p>实现方继承本类并提供固定输入与候选快照；平台升级后重跑即可验证兼容性。
 */
public abstract class CandidatePostProcessorContract {

    protected abstract CandidatePostProcessor processor();

    protected abstract IntentInput input();

    protected abstract CandidateSet candidates();

    @Test
    void resultIsNonNullAndDeterministic() {
        CandidateSet first = processor().process(input(), candidates());
        CandidateSet second = processor().process(input(), candidates());

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void resultDoesNotExpandOrRewritePlatformCandidates() {
        CandidateSet baseline = candidates();
        CandidateSet result = processor().process(input(), baseline);
        Set<String> allowed = baseline.recall().candidates().stream()
                .map(candidate -> candidate.candidateId())
                .collect(Collectors.toSet());

        assertThat(result.recall().domainRouting()).isEqualTo(baseline.recall().domainRouting());
        assertThat(result.recall().degradedChannels())
                .isEqualTo(baseline.recall().degradedChannels());
        assertThat(result.recall().candidates()).allSatisfy(candidate -> {
            assertThat(candidate.candidateId()).isIn(allowed);
            assertThat(candidate).isEqualTo(baseline.recall().candidates().stream()
                    .filter(original -> original.candidateId().equals(candidate.candidateId()))
                    .findFirst().orElseThrow());
        });
    }
}
