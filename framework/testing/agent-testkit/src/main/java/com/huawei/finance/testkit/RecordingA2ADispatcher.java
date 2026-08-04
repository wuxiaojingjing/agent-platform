package com.huawei.finance.testkit;

import com.huawei.finance.a2a.A2ADispatcher;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A2A dispatcher test double that records every exact production envelope. */
public final class RecordingA2ADispatcher implements A2ADispatcher {

    private final Function<DelegationEnvelope, DelegationReceipt> response;
    private final CopyOnWriteArrayList<DelegationEnvelope> envelopes = new CopyOnWriteArrayList<>();

    public RecordingA2ADispatcher(Function<DelegationEnvelope, DelegationReceipt> response) {
        this.response = response;
    }

    @Override
    public DelegationReceipt dispatch(DelegationEnvelope envelope) {
        envelopes.add(envelope);
        return response.apply(envelope);
    }

    public List<DelegationEnvelope> envelopes() {
        return List.copyOf(envelopes);
    }
}
