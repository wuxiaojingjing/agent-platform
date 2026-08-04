package com.huawei.finance.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.validation.ContractValidator;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.AssetLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FP-37 答案侧合规钩子。
 *
 * <p>这一组用例守的不是「审核逻辑对不对」——A 线还没有任何审核逻辑，口径要等业务与合规给。
 * 守的是**这个位置的性质**：它拦得住所有出口（含兜底）、拒绝时不放原文出去、审核器自己崩了
 * 不算放行、且在 A 线一分钱开销都不加。这四条一旦写成断言，B 线插入真实审核器时就不必
 * 重新论证渲染链路的行为。
 */
class AnswerAuditTest {

    private static AssetBundle bundle;

    @BeforeAll
    static void load() {
        bundle = new AssetLoader(new ContractValidator()).load(AssetLoader.locateAssets());
    }

    /** 记下每一次送审，用来回答「这条出口到底有没有过审核点」。 */
    private static final class RecordingAudit implements AnswerAudit {
        private final List<AnswerDraft> seen = new ArrayList<>();
        private final AnswerVerdict verdict;

        RecordingAudit(AnswerVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public AnswerVerdict review(AnswerDraft draft) {
            seen.add(draft);
            return verdict;
        }
    }

    @Test
    @DisplayName("A 线直通：默认装配与显式 passThrough 渲染出同一句话，且不产生审核指标")
    void passThroughChangesNothing() {
        SimpleMeterRegistry defaultRegistry = new SimpleMeterRegistry();
        SimpleMeterRegistry explicitRegistry = new SimpleMeterRegistry();

        String byDefault = new TemplateRenderer(bundle, new TemplateVariableValidator(), defaultRegistry)
                .render(balancePlan()).text();
        String explicit = new TemplateRenderer(bundle, new TemplateVariableValidator(), explicitRegistry,
                AnswerAudit.passThrough()).render(balancePlan()).text();

        assertThat(byDefault).isEqualTo(explicit);

        // 「不引入延迟」在单测里能验的那一半：直通实现不打点、不分配、走的是同一个单例。
        // 放行也计数看着无害，但那是每次面客请求都要多付的一次 counter 查找与一条时序，
        // 而放行在 A 线是 100% 的常态，换不回任何信息
        assertThat(defaultRegistry.find(com.huawei.finance.obs.AgentMetrics.ANSWER_AUDIT).counters()).isEmpty();
        assertThat(AnswerAudit.passThrough()).isSameAs(AnswerAudit.passThrough());
    }

    @Test
    @DisplayName("未过审的答案不出门：换成兜底那句，原文一个字都不带")
    void blockedAnswerNeverReachesTheUser() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TemplateRenderer renderer = new TemplateRenderer(bundle, new TemplateVariableValidator(), registry,
                draft -> AnswerVerdict.block("QA_REDLINE", "命中红线词"));

        RenderedResponse rendered = renderer.render(balancePlan());

        assertThat(rendered.text()).doesNotContain("12,345.67").doesNotContain("尾号 8888");
        assertThat(rendered.fellBack()).isTrue();
        assertThat(rendered.reason()).isEqualTo("answer-blocked:QA_REDLINE");
        assertThat(registry.get(com.huawei.finance.obs.AgentMetrics.ANSWER_AUDIT)
                .tag(com.huawei.finance.obs.AgentMetrics.TAG_REASON, "QA_REDLINE").counter().count()).isEqualTo(1.0);
    }

    /**
     * §2.7.8 的教训：负向边界只写在正常路径上不够，兜底链上必须再拦一道。
     *
     * <p>外部同类系统就是这么失守的——工具描述里明写了「本工具不涉及用户个人信息查询」，
     * 然后兜底链绕过工具选择，一脚踏空到公网搜索。
     */
    @Test
    @DisplayName("兜底文本同样要过审，审核点不能只挂在正常路径上")
    void fallbackTextIsAuditedToo() {
        RecordingAudit audit = new RecordingAudit(AnswerVerdict.pass());
        TemplateRenderer renderer = new TemplateRenderer(bundle, new TemplateVariableValidator(),
                new SimpleMeterRegistry(), audit);

        RenderedResponse rendered = renderer.render(planWithTemplate("tpl.does.not.exist", "tpl.fallback.generic"));

        assertThat(rendered.fellBack()).isTrue();
        assertThat(audit.seen).hasSize(1);
        assertThat(audit.seen.get(0).fellBack()).isTrue();
        assertThat(audit.seen.get(0).usedTemplateKey()).isEqualTo("tpl.fallback.generic");
        assertThat(audit.seen.get(0).text()).isEqualTo(rendered.text());
    }

    @Test
    @DisplayName("连兜底模板都没有时的最后那句话，也从审核点过一遍")
    void lastResortTextIsAuditedToo() {
        RecordingAudit audit = new RecordingAudit(AnswerVerdict.pass());
        TemplateRenderer renderer = new TemplateRenderer(bundle, new TemplateVariableValidator(),
                new SimpleMeterRegistry(), audit);

        renderer.render(planWithTemplate("tpl.does.not.exist", "tpl.also.missing"));

        assertThat(audit.seen).hasSize(1);
        assertThat(audit.seen.get(0).usedTemplateKey()).isNull();
    }

    /**
     * 拒绝之后不再重试、不再送审。
     *
     * <p>这条不是洁癖：如果拒绝时换个模板重渲染再送审，遇上「对整个场景一律拒绝」的审核器
     * 就是死循环，而它发生在面客同步链路上。
     */
    @Test
    @DisplayName("一律拒绝的审核器只被调用一次，不会逐级重试成死循环")
    void rejectionDoesNotRecurse() {
        AtomicInteger calls = new AtomicInteger();
        TemplateRenderer renderer = new TemplateRenderer(bundle, new TemplateVariableValidator(),
                new SimpleMeterRegistry(), draft -> {
                    calls.incrementAndGet();
                    return AnswerVerdict.block("ALWAYS", "无条件拒绝");
                });

        RenderedResponse rendered = renderer.render(balancePlan());

        assertThat(calls.get()).isEqualTo(1);
        assertThat(rendered.text()).isNotBlank();
    }

    @Test
    @DisplayName("审核器抛异常按拒绝处理：它自己崩了不构成放行的理由")
    void auditFailureIsClosed() {
        TemplateRenderer renderer = new TemplateRenderer(bundle, new TemplateVariableValidator(),
                new SimpleMeterRegistry(), draft -> {
                    throw new IllegalStateException("审核服务不可用");
                });

        RenderedResponse rendered = renderer.render(balancePlan());

        assertThat(rendered.reason()).isEqualTo("answer-blocked:AUDIT_ERROR");
        assertThat(rendered.text()).doesNotContain("12,345.67");
    }

    @Test
    @DisplayName("串联时任一拒绝即拒绝，且后面的不再执行")
    void compositeShortCircuits() {
        AtomicInteger second = new AtomicInteger();
        AnswerAudit chain = AnswerAudit.of(List.of(
                draft -> AnswerVerdict.block("FIRST", "第一道就拦下"),
                draft -> {
                    second.incrementAndGet();
                    return AnswerVerdict.pass();
                }));

        AnswerVerdict verdict = chain.review(new AnswerDraft("任意文本", "tpl.x", false, balancePlan()));

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.code()).isEqualTo("FIRST");
        // 顺序即优先级：先跑代价低的，被拦下之后就不该再去调外部审核服务
        assertThat(second.get()).isZero();
    }

    @Test
    @DisplayName("空清单串联退化成直通，不是退化成拒绝")
    void emptyChainIsPassThrough() {
        assertThat(AnswerAudit.of(List.of())).isSameAs(AnswerAudit.passThrough());
        assertThat(AnswerAudit.of(null)).isSameAs(AnswerAudit.passThrough());
    }

    @Test
    @DisplayName("拒绝码为空时归一到 UNSPECIFIED，不让空字符串进指标标签")
    void blankCodeIsNormalised() {
        assertThat(AnswerVerdict.block("  ", "x").code()).isEqualTo("UNSPECIFIED");
        assertThat(AnswerVerdict.block(null, "x").code()).isEqualTo("UNSPECIFIED");
    }

    private static ResponsePlan balancePlan() {
        return ResponsePlan.builder()
                .traceId("trace-1")
                .sceneCode("cap.account.balance.query#EXECUTE_CAPABILITY")
                .responsePhase(Enums.ResponsePhase.FINAL)
                .templateKey("tpl.balance.result")
                .templateVersion("1.0.0")
                .renderMode(Enums.RenderMode.TEMPLATE)
                .slots(Map.of("accountAlias", "尾号 8888 储蓄卡", "availableBalance", "12,345.67",
                        "currency", "¥"))
                .channel("MOBILE_BANK")
                .fallbackTemplateKey("tpl.fallback.generic")
                .build();
    }

    private static ResponsePlan planWithTemplate(String templateKey, String fallbackKey) {
        return ResponsePlan.builder()
                .traceId("trace-1")
                .sceneCode("unknown#EXECUTE_CAPABILITY")
                .responsePhase(Enums.ResponsePhase.ERROR)
                .templateKey(templateKey)
                .templateVersion("unknown")
                .renderMode(Enums.RenderMode.TEMPLATE)
                .slots(Map.of())
                .channel("MOBILE_BANK")
                .fallbackTemplateKey(fallbackKey)
                .build();
    }
}
