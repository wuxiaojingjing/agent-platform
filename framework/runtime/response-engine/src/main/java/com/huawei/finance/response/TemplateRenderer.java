package com.huawei.finance.response;

import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.contracts.validation.ValidationOutcome;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.TemplateDef;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模板渲染（实施架构 §5 第 5 条：Freemarker 渲染 + JSON Schema 校验变量，不自研模板引擎）。
 *
 * <p>渲染前先校验变量，校验不过直接走兜底，不进 Freemarker。等 Freemarker 抛异常再兜底也能工作，
 * 但那时错误信息是「表达式求值失败」，看不出到底缺了哪个字段。
 *
 * <p>兜底只走一层。加载期已保证兜底链无环，运行期再递归下去只会把一次失败放大成一串失败。
 *
 * <p>所有出口——正常渲染、兜底、最后一句硬编码——统一经 {@link #audited} 收口，
 * 那里挂着 FP-37 的答案侧审核点。收在一处是刻意的：审核只要有一条旁路，它就等于不存在。
 */
public class TemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(TemplateRenderer.class);

    private static final String LAST_RESORT_TEXT = "抱歉，我暂时无法完成这个操作，请稍后再试。";

    private final AssetBundle bundle;
    private final TemplateVariableValidator variableValidator;
    private final MeterRegistry meterRegistry;
    private final AnswerAudit answerAudit;
    private final Configuration freemarker;

    public TemplateRenderer(AssetBundle bundle, TemplateVariableValidator variableValidator,
                            MeterRegistry meterRegistry) {
        this(bundle, variableValidator, meterRegistry, AnswerAudit.passThrough());
    }

    public TemplateRenderer(AssetBundle bundle, TemplateVariableValidator variableValidator,
                            MeterRegistry meterRegistry, AnswerAudit answerAudit) {
        this.bundle = bundle;
        this.variableValidator = variableValidator;
        this.meterRegistry = meterRegistry;
        this.answerAudit = answerAudit == null ? AnswerAudit.passThrough() : answerAudit;
        this.freemarker = new Configuration(Configuration.VERSION_2_3_34);
        this.freemarker.setDefaultEncoding("UTF-8");
        // 面客链路不接受「把异常堆栈渲染进回复」。出错就抛，由本类接住并兜底
        this.freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        this.freemarker.setLogTemplateExceptions(false);
        this.freemarker.setWrapUncheckedExceptions(true);
        this.freemarker.setFallbackOnNullLoopVariable(false);
    }

    public RenderedResponse render(ResponsePlan plan) {
        TemplateDef template = bundle.templates().get(plan.templateKey());
        if (template == null) {
            log.error("模板不存在 key={}", plan.templateKey());
            return fallback(plan, "template-not-found");
        }

        ValidationOutcome outcome = variableValidator.validate(template, plan.slots());
        if (!outcome.valid()) {
            log.warn("模板变量不合 Schema key={} 原因={}", plan.templateKey(), outcome.summary());
            return fallback(plan, "variables-invalid");
        }

        try {
            String text = renderTemplate(template, plan.slots());
            meterRegistry.counter(AgentMetrics.TEMPLATE_RENDER,
                    AgentMetrics.TAG_TEMPLATE_KEY, template.key(),
                    AgentMetrics.TAG_OUTCOME, "HIT").increment();
            return audited(new RenderedResponse(text, template.key(), false, null, plan));
        } catch (Exception e) {
            log.error("模板渲染失败 key={} cause={}", template.key(), e.toString());
            return fallback(plan, "render-error");
        }
    }

    /**
     * 答案侧审核（FP-37）。
     *
     * <p>拒绝后不再尝试别的模板，直接落到那句不依赖任何资产的硬编码文案，并且**不再送审**。
     * 两个理由：换一个模板再渲染再送审，会把一次拒绝变成一串调用，而这条链路是面客同步链路；
     * 更要紧的是它可能不终止——审核器若对整个场景一律拒绝，逐级重试就是死循环。
     *
     * <p>审核器抛异常按拒绝处理。它自己崩了不构成放行的理由，而这行注释存在的原因是：
     * 反过来写（异常即放行）在代码里同样自然，出事时却没人分得清那是设计还是疏忽。
     */
    protected final RenderedResponse audited(RenderedResponse rendered) {
        AnswerVerdict verdict;
        try {
            verdict = answerAudit.review(new AnswerDraft(
                    rendered.text(), rendered.usedTemplateKey(), rendered.fellBack(), rendered.plan()));
        } catch (RuntimeException e) {
            log.error("答案审核器异常，按拒绝处理 template={} cause={}", rendered.usedTemplateKey(), e.toString());
            verdict = AnswerVerdict.block("AUDIT_ERROR", e.toString());
        }

        if (verdict == null || verdict.passed()) {
            return rendered;
        }

        log.warn("答案未过审 template={} code={} detail={}",
                rendered.usedTemplateKey(), verdict.code(), verdict.detail());
        meterRegistry.counter(AgentMetrics.ANSWER_AUDIT,
                AgentMetrics.TAG_OUTCOME, "BLOCK",
                AgentMetrics.TAG_REASON, verdict.code()).increment();
        return new RenderedResponse(LAST_RESORT_TEXT, null, true,
                "answer-blocked:" + verdict.code(), rendered.plan());
    }

    private RenderedResponse fallback(ResponsePlan plan, String reason) {
        String fallbackKey = plan.fallbackTemplateKey();
        TemplateDef fallback = fallbackKey == null ? null : bundle.templates().get(fallbackKey);

        meterRegistry.counter(AgentMetrics.TEMPLATE_RENDER,
                AgentMetrics.TAG_TEMPLATE_KEY, String.valueOf(plan.templateKey()),
                AgentMetrics.TAG_OUTCOME, "FALLBACK").increment();
        meterRegistry.counter(AgentMetrics.DEGRADED,
                AgentMetrics.TAG_COMPONENT, "response",
                AgentMetrics.TAG_REASON, reason).increment();

        if (fallback == null) {
            // 连兜底模板都没有：返回硬编码文案。这是整条链路最后一道防线，
            // 它不依赖任何资产，因此资产整体损坏时依然能给用户一句话
            return audited(new RenderedResponse(LAST_RESORT_TEXT, null, true, reason, plan));
        }

        try {
            return audited(new RenderedResponse(
                    renderTemplate(fallback, Map.of()), fallback.key(), true, reason, plan));
        } catch (Exception e) {
            log.error("兜底模板也渲染失败 key={} cause={}", fallback.key(), e.toString());
            return audited(new RenderedResponse(LAST_RESORT_TEXT, null, true, reason + "+fallback-failed", plan));
        }
    }

    private String renderTemplate(TemplateDef def, Map<String, Object> variables) throws Exception {
        Template template = new Template(def.key(), new StringReader(def.content()), freemarker);
        StringWriter writer = new StringWriter();
        template.process(variables, writer);
        return writer.toString().trim();
    }
}
