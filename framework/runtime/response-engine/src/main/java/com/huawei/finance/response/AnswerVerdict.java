package com.huawei.finance.response;

/**
 * 审核结论。
 *
 * <p>只有放行与拒绝两种，**没有「改一改再放行」**。让审核器改写答案等于给了它第二支笔，
 * 而改写的结果无人再审——这正是 §2.7.9 那条「模型自造一个看起来像行内域名的链接」的成因形态。
 * 需要换一句话时，正确做法是拒绝，由渲染层落到它自己那句可信的兜底文案。
 *
 * @param passed 是否放行
 * @param code   拒绝码，放行时为 null。进指标标签，因此必须是有限枚举值而非自由文本
 * @param detail 供日志与排障的细节，**不面客**
 */
public record AnswerVerdict(boolean passed, String code, String detail) {

    private static final AnswerVerdict PASS = new AnswerVerdict(true, null, null);

    public static AnswerVerdict pass() {
        return PASS;
    }

    public static AnswerVerdict block(String code, String detail) {
        return new AnswerVerdict(false, code == null || code.isBlank() ? "UNSPECIFIED" : code, detail);
    }
}
