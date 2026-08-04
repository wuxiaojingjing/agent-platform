package com.huawei.finance.agent.promptopt;

import com.huawei.finance.gateway.ChatRequest;
import com.huawei.finance.gateway.ModelGatewayClient;
import java.util.List;
import java.util.Optional;

/**
 * 让模型基于失败样本提出下一版提示词（SkillOpt 的「轨迹驱动编辑」那一步）。
 *
 * <p>三条刻意的约束，每条都对着一种已知的失败方式：
 *
 * <ol>
 *   <li><b>只给失败样本，不给资产也不给代码。</b>给了资产，它会去改资产的口径；
 *       给了代码，它会开始解释阈值。它能改的只有那段文字，多给的上下文只会让它建议
 *       它无权做的事，而那些建议读起来很有道理，很容易被人照做。</li>
 *   <li><b>要求最小编辑，并写清改了哪几条。</b>整段重写的候选就算分数更高也没法评审——
 *       没人能判断新的那两千字里有没有悄悄弱化一条约束。可评审性在这里比分数重要。</li>
 *   <li><b>把安全锚句原文列给它，明说不许动。</b>它仍可能动，所以
 *       {@link OptimizerGuard} 会逐字复查。写在提示里是为了少浪费几轮，
 *       真正拦住它的是那道门，不是这段嘱咐。</li>
 * </ol>
 */
public final class PromptEditor {

    private static final String SYSTEM = """
            你在改进一个银行智能助手「意图仲裁器」的系统提示词。

            你会看到：当前提示词全文，以及它在若干真实样本上判错的记录。
            每条记录包含用户输入、当时给模型的候选清单、期望结论、实际结论、差异标记。

            你的任务：对当前提示词做**最小改动**，让这些样本判对。

            硬约束：
            1. 下列句子必须逐字保留，一个字都不能改、不能删、不能加条件：
            %s
            2. 不得整段重写。改动应当集中在少数几条规则或示例上。
            3. 不得放宽「不确定时偏向澄清或慢路径」的取向。误执行的代价远高于多问一句。
            4. 提示词总长不得超过 %d 字。

            输出格式（严格照此，不要任何额外说明）：
            ===CHANGES===
            用不超过五行说明你改了哪几条，以及为什么这样改能让哪些样本判对。
            ===PROMPT===
            改动后的提示词全文。
            """.formatted(
            String.join("\n", OptimizerGuard.ANCHORS.stream().map(a -> "   - " + a).toList()),
            OptimizerGuard.MAX_SYSTEM_CHARS);

    private final ModelGatewayClient gateway;
    private final String model;

    public PromptEditor(ModelGatewayClient gateway, String model) {
        this.gateway = gateway;
        this.model = model;
    }

    /** 一次编辑提案。{@code changes} 是给人读的，不参与打分，但会跟着候选一起落盘。 */
    public record Edit(String changes, String prompt) {
    }

    public Optional<Edit> propose(String current, List<ArbitrationScorer.Failure> failures) {
        // 只喂前若干条。全喂进去会把编辑意图摊薄成「一次改十件事」，
        // 而那种候选一旦不过门，没人分得清是哪一条改坏的
        String evidence = failures.stream()
                .limit(8)
                .map(f -> """
                        ── 样本 %s
                        用户输入：%s
                        候选清单：
                        %s
                        期望：%s
                        实际：%s（%s）
                        """.formatted(f.caseId(), f.query(), f.userPrompt(),
                        f.expected(), f.got(), f.note()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("（无失败样本）");

        String user = "当前提示词（引号内为全文）：\n「\n" + current + "\n」\n\n判错记录：\n" + evidence;

        // temperature 不取 0：这里要的是不同的编辑方案，而不是稳定复现同一个方案。
        // 仲裁那边取 0 是因为线上要可复现，两处目的相反
        String raw = GatewayRetry.chat(gateway,
                new ChatRequest(model, SYSTEM, user, 4096, 0.7, false), "编辑提案");
        return parse(raw);
    }

    static Optional<Edit> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int changesAt = raw.indexOf("===CHANGES===");
        int promptAt = raw.indexOf("===PROMPT===");
        if (promptAt < 0) {
            return Optional.empty();
        }
        String changes = changesAt >= 0 && changesAt < promptAt
                ? raw.substring(changesAt + "===CHANGES===".length(), promptAt).trim()
                : "（模型未按格式说明改动）";
        String prompt = stripWrappers(
                raw.substring(promptAt + "===PROMPT===".length()).trim());
        return prompt.isBlank() ? Optional.empty() : Optional.of(new Edit(changes, prompt));
    }

    /**
     * 去掉模型顺手抄回来的包裹符。
     *
     * <p>实测第一版用 {@code <<<} / {@code >>>} 框住当前提示词，模型就把这两行原样抄进了输出，
     * 于是候选文件里躺着两行垃圾。它不影响打分（模型看得懂），但人一旦照着这份候选替换上线，
     * 提示词里就多了两行谁也解释不清的符号。包裹符已换成引号，这里仍然兜一层：
     * 这类抄回来的边框有很多花样，靠换分隔符是防不住的。
     */
    private static String stripWrappers(String prompt) {
        String s = prompt;
        for (String fence : List.of("<<<", ">>>", "```", "「", "」")) {
            s = s.lines()
                    .filter(line -> !line.trim().equals(fence))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
        }
        return s.trim();
    }
}
