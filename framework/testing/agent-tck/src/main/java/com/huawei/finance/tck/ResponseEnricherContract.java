package com.huawei.finance.tck;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.runtime.extension.ResponseEnricher;
import com.huawei.finance.runtime.extension.ResponseEnrichmentContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 业务 {@link ResponseEnricher} 的最低契约测试。 */
public abstract class ResponseEnricherContract {

    protected abstract ResponseEnricher enricher();

    protected abstract ResponseEnrichmentContext context();

    @Test
    void outputIsNonNullAndDeterministic() {
        Map<String, Object> first = enricher().enrich(context());
        Map<String, Object> second = enricher().enrich(context());

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void enrichmentCannotMutateRuntimeFacts() {
        ResponseEnrichmentContext before = context();
        String traceId = before.context().traceId();
        String sessionId = before.context().sessionId();
        Object decision = before.decision();
        Object intentPlan = before.intentPlan();
        String taskId = before.taskId();
        Object taskResult = before.taskResult();
        Object guardrail = before.guardrail();
        Map<String, Object> renderSlots = before.renderSlots();
        enricher().enrich(before);

        assertThat(before.context().traceId()).isEqualTo(traceId);
        assertThat(before.context().sessionId()).isEqualTo(sessionId);
        assertThat(before.decision()).isSameAs(decision);
        assertThat(before.intentPlan()).isSameAs(intentPlan);
        assertThat(before.taskId()).isEqualTo(taskId);
        assertThat(before.taskResult()).isSameAs(taskResult);
        assertThat(before.guardrail()).isSameAs(guardrail);
        assertThat(before.renderSlots()).isEqualTo(renderSlots);
    }
}
