package com.huawei.finance.context;

import com.huawei.finance.contracts.port.ContextStateVersionProvider;
import java.util.List;

public final class TurnStoreContextStateVersionProvider implements ContextStateVersionProvider {
    private final TurnStore turns;

    public TurnStoreContextStateVersionProvider(TurnStore turns) {
        this.turns = turns;
    }

    @Override
    public long currentVersion(String tenantId, String agentId, String sessionId) {
        List<ConversationTurn> recent = turns.recent(tenantId, agentId, sessionId, 1);
        return recent.isEmpty() ? 0 : recent.getLast().seq() + 1;
    }
}
