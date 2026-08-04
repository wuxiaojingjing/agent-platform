package com.huawei.finance.contracts.port;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.TaskResult;
import com.huawei.finance.contracts.model.UnifiedTask;
import com.huawei.finance.stability.Spi;
import java.util.Optional;

/**
 * 把一条能力交给**别的 Agent** 去办（架构草案 §8.5、§12 第 4 条）。
 *
 * <p>它与 {@link DomainAgent} 并列在同一个位置上被选择：中控走到「谁去办这件事」那一步时，
 * 先问本接口，不接才落到本地 {@code DomainAgent}。这个位置是刻意的——
 * 它在护栏、租约闸与幂等键**之后**。
 *
 * <p><b>为什么必须是这个位置。</b>此前委托挂在 {@code orchestrator.handle} 的外面，
 * 由入口二选一：能委托就委托，否则走中控。而护栏、{@code CONTEXT_UNAVAILABLE} 副作用闸、
 * 本地任务建档与状态机全都长在 {@code handle} 里面，于是「换一条执行通道」顺带换掉了
 * 三道判断——白名单域上一笔已确认的超限转账，{@code AMOUNT_LIMIT_EXCEEDED} 谁都不查。
 * 那三道判断与「谁去办」无关，不该被投递方式左右。
 *
 * <p>换到本接口之后，草案 §8.5 那张顺序图（本地护栏判断 → 本地执行或 A2A 委托）
 * 由结构保证，而不再依赖两条通道各自记得跑一遍。
 *
 * <p><b>返回空表示「这条路走不通」，不表示「这件事办不成」。</b>
 * 两者的回落方向相反:走不通该回落本地执行（用户不该因为一次内部路由选择而办不成事）;
 * 办不成必须原样返回（下游可能已经动手，回落等于再办一次）。因此实现方只在
 * <b>确知目标没有收到这笔业务</b>时才返回 {@link Optional#empty()}。
 */
@Spi
public interface CapabilityDelegator {

    /**
     * 这条能力是否改由外部 Agent 承接。
     *
     * <p>先问一次而不是直接调 {@link #delegate}，是为了让「不适用」不必构造信封、
     * 不必占用委托台账里的一个 id。
     */
    boolean handles(String capabilityId);

    /**
     * 委托一次。
     *
     * @param task 已过护栏、已带幂等键的任务。{@code parameters} 已被
     *     {@link CapabilityCard#ownedSlots} 筛过——跨 Agent 只送卡声明的槽位（§8.1 白名单），
     *     而不是入口抽到的全部
     * @param card 目标能力卡，供实现方推导超时预算与目标域
     * @return 终态结果；确知目标未收到这笔业务时返回 {@link Optional#empty()}，
     *     由中控回落本地执行
     */
    Optional<TaskResult> delegate(UnifiedTask task, CapabilityCard card);
}
