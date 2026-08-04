package com.huawei.finance.response;

import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.ResponsePlan;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.TemplateDef;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Realizes visible text while keeping the existing deterministic template as the safety baseline. */
public class ResponseRealizer extends TemplateRenderer {

    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}])\\d[\\d,.:%-]*");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final AssetBundle bundle;
    private final ResponseTextModel model;

    public ResponseRealizer(AssetBundle bundle, TemplateVariableValidator validator,
                            MeterRegistry meters, AnswerAudit audit, ResponseTextModel model) {
        super(bundle, validator, meters, audit);
        this.bundle = bundle;
        this.model = model == null ? ResponseTextModel.unavailable() : model;
    }

    @Override
    public RenderedResponse render(ResponsePlan plan) {
        return render(plan, ResponseModelContext.empty());
    }

    public RenderedResponse render(ResponsePlan plan, ResponseModelContext context) {
        RenderedResponse baseline = super.render(plan);
        if (plan.renderMode() == Enums.RenderMode.TEMPLATE || baseline.fellBack()) {
            return baseline;
        }

        Map<String, String> approved = approvedTemplates(plan);
        ResponseTextModel.Result result;
        try {
            result = model.realize(new ResponseTextModel.Request(
                    plan.renderMode(), plan.responseModel(), bundle.responsePolicy().getSystemPrompt(),
                    plan.responsePromptVersion(), plan.responseTemperature(), plan.responseMaxTokens(),
                    baseline.text(), approved, context.conversationHistory(), context.userQuery(),
                    context.committedFacts(), plan.riskNoticeCodes(), plan.responsePhase()));
        } catch (RuntimeException error) {
            return fallbackToBaseline(baseline, "response-model-error");
        }
        if (result == null || !result.available()) {
            return fallbackToBaseline(baseline, result == null ? "response-model-null" : result.reason());
        }

        if (plan.renderMode() == Enums.RenderMode.MODEL_SELECT) {
            if (result.templateKey() == null || !approved.containsKey(result.templateKey())) {
                return fallbackToBaseline(baseline, "template-selection-invalid");
            }
            RenderedResponse selected = super.render(withTemplate(plan, result.templateKey()));
            return selected.fellBack() ? fallbackToBaseline(baseline, "selected-template-invalid") : selected;
        }

        String text = result.text() == null ? "" : result.text().trim();
        String violation = safetyViolation(text, baseline.text(), plan, context);
        if (violation != null) {
            return fallbackToBaseline(baseline, violation);
        }
        return audited(new RenderedResponse(text, baseline.usedTemplateKey(), false,
                "model:" + plan.renderMode().name(), plan));
    }

    private Map<String, String> approvedTemplates(ResponsePlan plan) {
        Map<String, String> rendered = new LinkedHashMap<>();
        for (String key : plan.approvedTemplateKeys()) {
            TemplateDef def = bundle.templates().get(key);
            if (def == null || def.phase() != plan.responsePhase()) continue;
            RenderedResponse candidate = super.render(withTemplate(plan, key));
            if (!candidate.fellBack()) rendered.put(key, candidate.text());
        }
        return Map.copyOf(rendered);
    }

    private static String safetyViolation(String text, String baseline, ResponsePlan plan,
                                          ResponseModelContext context) {
        if (text.isBlank() || text.length() > 2000) return "model-text-invalid";
        if (!urls(text).stream().allMatch(urls(baseline)::contains)) return "model-url-invented";

        Set<String> allowedNumbers = numbers(baseline);
        context.committedFacts().values().forEach(value -> allowedNumbers.addAll(numbers(String.valueOf(value))));
        if (!allowedNumbers.containsAll(numbers(text))) return "model-number-invented";

        for (String key : Set.of("amount", "payee", "accountAlias", "fromAccount", "cardRef",
                "cardType", "availableBalance", "billAmount", "dueDate", "serialNo")) {
            Object value = plan.slots().get(key);
            if (value != null && baseline.contains(String.valueOf(value)) && !text.contains(String.valueOf(value))) {
                return "model-protected-fact-removed:" + key;
            }
        }
        if (!plan.riskNoticeCodes().isEmpty() && !text.contains(baseline)) {
            return "model-risk-notice-not-preserved";
        }
        if (plan.responsePhase() == Enums.ResponsePhase.CONFIRM && !text.contains("确认")) {
            return "model-confirmation-weakened";
        }
        return null;
    }

    private static Set<String> numbers(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static Set<String> urls(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = URL.matcher(text == null ? "" : text);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private static RenderedResponse fallbackToBaseline(RenderedResponse baseline, String reason) {
        return new RenderedResponse(baseline.text(), baseline.usedTemplateKey(), true,
                reason == null ? "response-model-unavailable" : reason, baseline.plan());
    }

    private static ResponsePlan withTemplate(ResponsePlan plan, String templateKey) {
        return ResponsePlan.builder()
                .traceId(plan.traceId()).taskId(plan.taskId()).sceneCode(plan.sceneCode())
                .responsePhase(plan.responsePhase()).templateKey(templateKey)
                .templateVersion(plan.templateVersion()).renderMode(Enums.RenderMode.TEMPLATE)
                .responseModel(plan.responseModel()).approvedTemplateKeys(plan.approvedTemplateKeys())
                .responseTemperature(plan.responseTemperature()).responseMaxTokens(plan.responseMaxTokens())
                .responsePolicyVersion(plan.responsePolicyVersion()).responsePromptVersion(plan.responsePromptVersion())
                .slots(plan.slots()).cardComponents(plan.cardComponents()).actionCodes(plan.actionCodes())
                .riskNoticeCodes(plan.riskNoticeCodes()).channel(plan.channel()).locale(plan.locale())
                .fallbackTemplateKey(plan.fallbackTemplateKey()).build();
    }
}
