package com.huawei.finance.response;

/**
 * A 线的答案侧审核实现：什么都不做。
 *
 * <p>单例且方法体只有一条 return，为的是让「留了扩展点」不必用「多了一跳开销」来换。
 * FP-37 的判定里那句「A 线为直通实现且不引入延迟」指的就是这个类。
 */
final class PassThroughAnswerAudit implements AnswerAudit {

    static final PassThroughAnswerAudit INSTANCE = new PassThroughAnswerAudit();

    private PassThroughAnswerAudit() {
    }

    @Override
    public AnswerVerdict review(AnswerDraft draft) {
        return AnswerVerdict.pass();
    }
}
