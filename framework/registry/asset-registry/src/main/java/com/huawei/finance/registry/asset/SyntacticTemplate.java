package com.huawei.finance.registry.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 句法模版：标准问的写法（FP-1I）。
 *
 * <p>业界（小 i 一系的问答机器人）通行的做法是让业务人员用模版而不是正则写相似问法，
 * 一条模版顶几十条穷举。本实现只取其中**能被静态检查住**的三个记号，多一个都不加：
 *
 * <ul>
 *   <li>{@code {甲|乙|丙}} 择一。写同义说法用它。
 *   <li>{@code [的]} 可省。写可有可无的虚词用它。
 *   <li>{@code *} 任意若干字，**受长度上限约束**（{@value #WILDCARD_MAX}）。
 *   <li>其余字符一律字面匹配，包括标点。
 * </ul>
 *
 * <p><b>刻意不支持正则</b>。放开正则，模版就成了业务人员写不了、工程也审不动的东西：
 * 一个写歪的 {@code .*} 会把整个标准问答库变成一条恒命中的规则，而它命中之后模型仲裁根本
 * 不会被执行，故障形态是「机器人开始对所有问题念同一段话」。上面三个记号编译出来的正则
 * 是本文件生成的，形状可控。
 *
 * <p><b>整句锚定</b>。匹配的是整句而非片段：片段匹配下「我不想查余额」会命中「查余额」，
 * 而否定句正是问答机器人最经典的翻车点。
 *
 * <p><b>过宽的模版直接拒绝编译</b>，见 {@link #compile}。宁可在 CI 上红，
 * 也不要让它上线之后靠人去发现。
 */
public final class SyntacticTemplate {

    /** 通配符最多吃多少个字。银行场景里一句话的可变部分不会比这更长。 */
    private static final int WILDCARD_MAX = 20;

    /** 模版至少要有这么多个字面字符，否则视为过宽。 */
    private static final int MIN_LITERAL_CHARS = 2;

    private final String source;
    private final Pattern pattern;

    private SyntacticTemplate(String source, Pattern pattern) {
        this.source = source;
        this.pattern = pattern;
    }

    /**
     * @throws IllegalArgumentException 模版为空、记号不配对，或字面字符太少（过宽）
     */
    public static SyntacticTemplate compile(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("句法模版不能为空");
        }
        StringBuilder regex = new StringBuilder();
        int literals = translate(template.trim(), regex);

        if (literals < MIN_LITERAL_CHARS) {
            // 「*余额*」这种只剩一个词的模版会把大半流量吸进标准答案，而标准答案一旦命中
            // 就不再走召回与模型。宽到这个程度的规则，作者八成是想写「包含」而不是「就是」
            throw new IllegalArgumentException(
                    "句法模版过宽，字面字符不足 " + MIN_LITERAL_CHARS + " 个：" + template);
        }
        return new SyntacticTemplate(template.trim(), Pattern.compile(regex.toString()));
    }

    /**
     * 把模版翻成正则，返回字面字符数。
     *
     * <p>字面字符数用来判过宽：择一分支里的字不算——{@code {查|看|瞧}} 只贡献一个位置的约束，
     * 按里面的总字数算会让一条 {@code {查询|看一下|瞅一眼}*} 轻松过关。
     */
    private static int translate(String template, StringBuilder regex) {
        int literals = 0;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            switch (c) {
                case '{' -> {
                    int close = matching(template, i, '{', '}');
                    regex.append("(?:").append(alternatives(template.substring(i + 1, close))).append(')');
                    literals++;
                    i = close + 1;
                }
                case '[' -> {
                    int close = matching(template, i, '[', ']');
                    StringBuilder inner = new StringBuilder();
                    translate(template.substring(i + 1, close), inner);
                    regex.append("(?:").append(inner).append(")?");
                    i = close + 1;
                }
                case '*' -> {
                    regex.append(".{0,").append(WILDCARD_MAX).append("}");
                    i++;
                }
                default -> {
                    regex.append(Pattern.quote(String.valueOf(c)));
                    if (!Character.isWhitespace(c)) {
                        literals++;
                    }
                    i++;
                }
            }
        }
        return literals;
    }

    private static String alternatives(String body) {
        List<String> parts = new ArrayList<>();
        for (String part : body.split("\\|", -1)) {
            if (part.isEmpty()) {
                // 空分支等价于「这一段可省」，那是 [] 的职责。允许它会让 {甲|} 与 [甲]
                // 有两种写法而语义相同，审模版的人得记两套
                throw new IllegalArgumentException("择一记号里有空分支，可省请用 []：" + body);
            }
            StringBuilder inner = new StringBuilder();
            translate(part, inner);
            parts.add(inner.toString());
        }
        if (parts.size() < 2) {
            throw new IllegalArgumentException("择一记号至少要有两个分支：" + body);
        }
        return String.join("|", parts);
    }

    private static int matching(String template, int open, char openChar, char closeChar) {
        int depth = 0;
        for (int i = open; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == openChar) {
                depth++;
            } else if (c == closeChar && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("句法模版里的 " + openChar + " 没有配对的 " + closeChar
                + "：" + template);
    }

    /** 整句匹配。片段命中不算命中，理由见类注释。 */
    public boolean matches(String text) {
        if (text == null) {
            return false;
        }
        Matcher matcher = pattern.matcher(text.trim());
        return matcher.matches();
    }

    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return source;
    }
}
