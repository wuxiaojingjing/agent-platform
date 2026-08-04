package com.huawei.finance.a2a;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.util.Optional;

/**
 * 委托台账（架构草案 v0.2 §6.2）。
 *
 * <p>网关用它做**入站去重**:{@code delegationId} 是下游唯一的去重依据，
 * 下游本地幂等键只对它自己的子任务与更下层的调用有效，不参与入站去重。
 *
 * <p>{@link #claim} 与 {@link #settle} 分开，是因为「已受理但还没有结果」是一个必须能表达的状态:
 * 合并成一步的话，二次到达时要么读到「没见过」（于是重跑一遍），
 * 要么必须等首次跑完才落库（于是并发重投时两边都认为自己是首次）。
 */
public interface DelegationStore {

    /**
     * 受理一次委托，落台账。
     *
     * @return 空表示首次受理，可以往下执行；非空表示这个 delegationId 见过了，
     *         调用方必须原样返回其中的首次结果——{@link Claim#settled()} 为 false 时
     *         表示首次执行还在进行中，此时回 PARTIAL 而不是重跑
     */
    Optional<Claim> claim(DelegationEnvelope envelope);

    /** 落首次结果。同一 delegationId 只落一次，重复落盘不覆盖。 */
    void settle(String delegationId, DelegationReceipt receipt);

    /** 已受理的委托快照。 */
    record Claim(String delegationId, boolean settled, DelegationReceipt receipt) {}
}
