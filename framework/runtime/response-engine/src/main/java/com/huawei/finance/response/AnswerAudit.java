package com.huawei.finance.response;

import java.util.List;

/**
 * 答案侧审核点（FP-37）。
 *
 * <p>审的是**生成出来的那句话**，而不是生成它的计划。渲染前已经有变量 Schema 校验与风险提示，
 * 但那两道都在看输入；一旦 B 线的润色模型介入，输出就不再是模板的确定性产物，
 * 输入合法而输出违规成为可能（§2.7.5：外部同类系统正是在这里被标了 QA_Redline）。
 *
 * <p>A 线全模板输出，因此默认实现是 {@link #passThrough()}——不做任何检查、不产生任何开销。
 * 现在就把这个位置留出来的理由是：润色上线那天再插进来，得改渲染链路；
 * 而渲染链路是所有出口的必经之地，那时的改动风险远大于现在。
 *
 * <p>挂在 {@link TemplateRenderer} 内部而不是更外层的 ChatService，是为了让兜底文本也过审。
 * §2.7.8 记的那条教训就在这里：负向边界只写在正常路径上不够，兜底链上必须再拦一道，
 * 否则前面守得再好，兜底一脚就能踏空。
 */
public interface AnswerAudit {

    /**
     * 审一条待返回的答案。
     *
     * <p>实现必须是确定性的、不得抛异常。抛出的异常由调用方接住并按拒绝处理——
     * 审核器自己崩了不能成为放行的理由。
     */
    AnswerVerdict review(AnswerDraft draft);

    /** A 线直通：恒放行，不分配、不打点、不触网。 */
    static AnswerAudit passThrough() {
        return PassThroughAnswerAudit.INSTANCE;
    }

    /**
     * 串联多个审核器，任一拒绝即拒绝，且**就地短路**——后面的不再执行。
     *
     * <p>顺序即优先级：先跑代价低的（关键词、URL 白名单），再跑要调外部服务的。
     */
    static AnswerAudit of(List<AnswerAudit> audits) {
        List<AnswerAudit> effective = audits == null ? List.of() : List.copyOf(audits);
        if (effective.isEmpty()) {
            return passThrough();
        }
        if (effective.size() == 1) {
            return effective.get(0);
        }
        return draft -> {
            for (AnswerAudit audit : effective) {
                AnswerVerdict verdict = audit.review(draft);
                if (verdict != null && !verdict.passed()) {
                    return verdict;
                }
            }
            return AnswerVerdict.pass();
        };
    }
}
