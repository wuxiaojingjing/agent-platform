package com.huawei.finance.agent.promptopt;

import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.ModelGatewayClient;
import java.util.List;
import java.util.Optional;

/** Produces minimal context-rewrite prompt edits from failed frozen trajectories. */
public final class ContextPromptEditor {

    private static final String SYSTEM = """
            你在改进银行智能助手的上下文改写系统提示词。只允许修改提示词，不改代码、Schema、知识和样本真值。
            根据失败轨迹做最小编辑；不得添加具体用户话术的 few-shot 示例，不得弱化安全约束。
            先在推理阶段比较失败的共同原因、相互冲突的约束和潜在回归，再给出最终编辑。
            最终答案只保留可审计的改动依据与完整提示词，不输出逐 token 思维链。
            必须为最终答案预留至少 4000 token，不能把全部输出预算耗在推理阶段。
            以下锚句必须逐字保留：
            %s
            总长度不得超过 %d 字。
            输出严格为：
            ===CHANGES===
            不超过五行的改动说明
            ===PROMPT===
            修改后的完整 system 提示词
            """.formatted(
            String.join("\n", ContextOptimizerGuard.ANCHORS.stream().map(a -> "- " + a).toList()),
            ContextOptimizerGuard.MAX_SYSTEM_CHARS);

    private final ModelGatewayClient gateway;
    private final String model;

    public ContextPromptEditor(ModelGatewayClient gateway, String model) {
        this.gateway = gateway;
        this.model = model;
    }

    public record Edit(String changes, String prompt) {
    }

    Optional<Edit> propose(String current, List<ContextRewriteScorer.Failure> failures,
                           String previousRuling) {
        String evidence = failures.stream().limit(6).map(f -> """
                -- %s
                用户输入：%s
                冻结 user prompt：
                %s
                期望：%s
                实际：%s
                差异：%s
                """.formatted(f.caseId(), f.query(), compactPrompt(f.userPrompt()),
                        f.expected(), f.got(), f.note()))
                .reduce((a, b) -> a + "\n" + b).orElse("无失败");
        String feedback = previousRuling == null || previousRuling.isBlank()
                ? "无" : previousRuling;
        String user = "当前 system 字符数=" + current.length()
                + "，候选必须控制在 3100 字符以内；空间不足时压缩或替换已有重复规则，禁止只追加。"
                + "\n上一轮门控反馈：" + feedback
                + "\n\n当前 system：\n「\n" + current + "\n」\n\n失败轨迹：\n" + evidence;
        String raw = GatewayRetry.chat(gateway,
                new ChatRequest(model, SYSTEM, user, 32768, 0.7, false, null, "prompt-optimization"),
                "context 编辑提案");
        Optional<Edit> parsed = parse(raw);
        if (parsed.isEmpty()) {
            String preview = raw == null ? "null" : raw.substring(0, Math.min(raw.length(), 600));
            System.out.println("编辑器最终 content 未匹配协议，长度="
                    + (raw == null ? 0 : raw.length()) + "，预览：\n" + preview);
        }
        return parsed;
    }

    private static String compactPrompt(String prompt) {
        if (prompt == null || prompt.length() <= 2400) {
            return prompt;
        }
        return prompt.substring(0, 1200)
                + "\n...（中段仅为重复结构，编辑输入省略）...\n"
                + prompt.substring(prompt.length() - 1200);
    }

    static Optional<Edit> parse(String raw) {
        if (raw == null) return Optional.empty();
        int changesAt = raw.indexOf("===CHANGES===");
        int promptAt = raw.indexOf("===PROMPT===");
        if (promptAt < 0) return Optional.empty();
        String changes = changesAt >= 0 && changesAt < promptAt
                ? raw.substring(changesAt + 13, promptAt).trim() : "未说明";
        String prompt = raw.substring(promptAt + 12).trim();
        prompt = prompt.replaceFirst("^```(?:yaml|text)?\\s*", "").replaceFirst("\\s*```$", "");
        if (prompt.startsWith("「") && prompt.endsWith("」")) {
            prompt = prompt.substring(1, prompt.length() - 1).trim();
        }
        return prompt.isBlank() ? Optional.empty() : Optional.of(new Edit(changes, prompt));
    }
}
