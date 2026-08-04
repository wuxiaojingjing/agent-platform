package com.huawei.finance.fastpath.arbitration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

/**
 * 注入仲裁 prompt 的日期基准表（v0.7 §2.7.6）。
 *
 * <p><b>为什么要这张表。</b>「上个月的账单」里的「上个月」在模型权重里是没有答案的——
 * 它不知道今天几号。不给基准，模型要么留空（多问一句本可以不问的话），要么按训练语料里的
 * 某个日期去推（更糟：算出来的是一个看着合理的错日期，下游无从分辨）。
 *
 * <p><b>为什么算好了再给，而不是只给「今天」。</b>「上月 1 号到上月最后一天」这类跨月边界
 * 交给模型算，等于把日期运算这件确定性的事交给一个近似确定的东西。§2.7 把金额与卡号解析
 * 列为禁止用模型，理由同此：能算准的就别让它猜。表里给区间，模型只需**选**一行。
 *
 * <p><b>为什么收时钟而不是 {@code LocalDate.now()}。</b>不然这张表每天都不一样，
 * 任何断言 prompt 内容的用例都会在跨日时莫名其妙地红一次，而人第一反应是「测试不稳定」，
 * 不是「日期注入错了」。
 */
public final class DateTable {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private DateTable() {
    }

    /** 渲染成 prompt 里的一小段文本。控制在几行以内——它每次请求都要占字符预算。 */
    public static String render(Clock clock) {
        LocalDate today = LocalDate.now(clock);
        LocalDate monthStart = today.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate lastMonth = today.minusMonths(1);

        return """
                今天=%s 昨天=%s 前天=%s
                本月=%s~%s 上月=%s~%s
                最近7天=%s~%s 最近30天=%s~%s""".formatted(
                ISO.format(today), ISO.format(today.minusDays(1)), ISO.format(today.minusDays(2)),
                ISO.format(monthStart), ISO.format(today),
                ISO.format(lastMonth.with(TemporalAdjusters.firstDayOfMonth())),
                ISO.format(lastMonth.with(TemporalAdjusters.lastDayOfMonth())),
                ISO.format(today.minusDays(6)), ISO.format(today),
                ISO.format(today.minusDays(29)), ISO.format(today));
    }
}
