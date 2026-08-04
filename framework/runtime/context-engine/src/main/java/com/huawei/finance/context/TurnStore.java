package com.huawei.finance.context;

import com.huawei.finance.stability.Spi;
import java.util.List;

/**
 * 对话轮次存取。
 *
 * <p>读失败必须抛，不许静默返回空列表。这是与出口缓存最关键的区别：缓存读不到只是变慢，
 * 而上下文读不到会让系统以为「用户之前什么都没说过」，进而把一次续办当成新指令去执行。
 * 空历史与读不到历史必须能被区分，否则 FP-28 那条「上下文异常时有副作用操作停止」
 * 在代码里就没有触发点。
 */
@Spi
public interface TurnStore {

    /** 追加一轮。序号由实现分配，保证会话内单调递增。 */
    ConversationTurn append(ConversationTurn turn);

    /**
     * 取某 Agent 会话最近若干轮，按时间正序。
     *
     * @throws ContextUnavailableException 读取失败。调用方据此降级，不得当作空历史
     */
    List<ConversationTurn> recent(String tenantId, String agentId, String sessionId, int limit);

    /** Offline/test compatibility; online callers must use the tenant-scoped overload. */
    default List<ConversationTurn> recent(String agentId, String sessionId, int limit) {
        return recent(com.huawei.finance.common.context.RequestContext.SPACE_UNSCOPED,
                agentId, sessionId, limit);
    }
}
