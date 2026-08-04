package com.huawei.finance.a2a;

import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内委托台账。
 *
 * <p>用途明确:单实例测试与本地联调。**不是**生产实现——它守不住 §6.2 第 2 条
 * 「{@code delegationId} 落进下游任务表并做唯一约束」,因为多实例时各自一份 Map,
 * 同一委托投到两个实例上会各自认为自己是首次。生产走 {@link PostgresDelegationStore}。
 *
 * <p>{@code putIfAbsent} 而不是先查后写:后者在并发重投下两个线程都会读到「没见过」。
 * 这个竞态在单机压测里就能复现，不需要多实例。
 */
public class InMemoryDelegationStore implements DelegationStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<Claim> claim(DelegationEnvelope envelope) {
        Entry fresh = new Entry(null);
        Entry existing = entries.putIfAbsent(envelope.delegationId(), fresh);
        if (existing == null) {
            return Optional.empty();
        }
        return Optional.of(new Claim(envelope.delegationId(),
                existing.receipt != null, existing.receipt));
    }

    @Override
    public void settle(String delegationId, DelegationReceipt receipt) {
        // 只落一次：重复落盘不覆盖，否则「返回首次结果」就成了「返回最后一次结果」
        entries.computeIfPresent(delegationId,
                (k, v) -> v.receipt == null ? new Entry(receipt) : v);
        entries.putIfAbsent(delegationId, new Entry(receipt));
    }

    private record Entry(DelegationReceipt receipt) {
    }
}
