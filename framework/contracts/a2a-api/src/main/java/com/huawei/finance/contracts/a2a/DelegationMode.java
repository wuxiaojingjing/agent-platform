package com.huawei.finance.contracts.a2a;

import com.huawei.finance.stability.Api;

/**
 * 委托模式（架构草案 v0.2 §6.1）。
 *
 * <p>两种模式的风险**不对等**，这是本枚举存在的全部理由:
 *
 * <ul>
 *   <li>{@link #TASK}——上游已确定能力 ID 与参数，下游执行一笔明确任务。
 *       下游没有「重新理解」的空间，所以也没有编造的空间。
 *   <li>{@link #GOAL}——上游把目标交给下游，下游自己识别、规划、编排。
 *       这正是 OJ 接入时踩过的坑复活的地方:若下游错配到一个通用对话 Handler，
 *       它会把自由文本喂给大模型，模型很可能回一句「已为您转账 1000 元」——
 *       看起来完全正常，中控会把一笔从未发生的转账记为成功。
 * </ul>
 *
 * <p>因此 <b>GOAL 的回执校验必须比 TASK 更严，不是更松</b>:缺信封或版本不符即整单
 * {@code FATAL}，宁可拒绝也不猜。见 {@link DelegationReceipt#requireValidEnvelope}。
 */
@Api
public enum DelegationMode {

    /** 已确定能力与参数的短路径（含域节点互调）。 */
    TASK,

    /**
     * 把目标交给下游自治 Agent。
     *
     * <p>纯执行器（节点内 {@code DomainAgent}）收到 GOAL 一律拒绝，
     * 而不是尽力猜一个能力去执行。
     */
    GOAL
}
