package com.huawei.finance.common.context;

import com.huawei.finance.stability.Api;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次请求的调用上下文。
 *
 * <p>除了 Trace 与渠道信息，它还**统计模型网关往返次数**（用途序列）。
 * 次数本身不再设硬上限（ADR-003）；记账留给看板与延迟分析，不拿来掐请求、也不拿来否决重排。
 */
@Api
public class RequestContext {

    /**
     * 未限定租户。
     *
     * <p>只有库内单测与离线回放该用它——那些场景本来就不存在跨租户的概念。
     * 面客入口一律要求显式租户，缺头即拒绝（FP-65），因此这个取值不会出现在线上请求上。
     * 用一个可搜索的常量而不是空串，是为了让它万一真出现在缓存键或日志里时能被认出来。
     */
    public static final String SPACE_UNSCOPED = "-";

    /**
     * 当前单体入口 Agent（架构草案阶段 1）。
     *
     * <p>多 Agent 之前所有请求都落在这个身份上；作用域键与库表用它做默认分片，
     * 避免空串或 null 混进 Redis / Postgres。
     */
    public static final String AGENT_ENTRY = "agent.entry";

    private final String traceId;
    private final String sessionId;
    private final String userId;
    private final String spaceId;
    private final String agentId;
    private final String channel;
    private final String page;
    private final String userState;
    private final PrincipalState principal;
    private final InvocationLineage lineage;

    /** 本轮是否为澄清补充后的重试。为真时必须绕过一级出口缓存（v0.7 §3.4）。 */
    private final boolean clarifyRetry;

    private final AtomicInteger gatewayRoundTrips = new AtomicInteger();
    private final List<String> gatewayCalls = new ArrayList<>();
    private final List<RuntimeModuleStep> moduleSteps = new ArrayList<>();

    /** 库内与测试用：租户维度按 {@link #SPACE_UNSCOPED}、Agent 按 {@link #AGENT_ENTRY}。 */
    public RequestContext(String traceId, String sessionId, String userId, String channel,
                          String page, String userState, boolean clarifyRetry) {
        this(traceId, sessionId, userId, SPACE_UNSCOPED, AGENT_ENTRY, channel, page, userState,
                clarifyRetry);
    }

    /** 面客链路常用：显式租户，Agent 默认 {@link #AGENT_ENTRY}。 */
    public RequestContext(String traceId, String sessionId, String userId, String spaceId,
                          String channel, String page, String userState, boolean clarifyRetry) {
        this(traceId, sessionId, userId, spaceId, AGENT_ENTRY, channel, page, userState, clarifyRetry);
    }

    public RequestContext(String traceId, String sessionId, String userId, String spaceId,
                          String agentId, String channel, String page, String userState,
                          boolean clarifyRetry) {
        this(traceId, sessionId, userId, spaceId, agentId, channel, page, userState,
                clarifyRetry, defaultPrincipal(userId, channel), null);
    }

    public RequestContext(String traceId, String sessionId, String userId, String spaceId,
                          String agentId, String channel, String page, String userState,
                          boolean clarifyRetry, PrincipalState principal,
                          InvocationLineage lineage) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.spaceId = spaceId == null || spaceId.isBlank() ? SPACE_UNSCOPED : spaceId;
        this.agentId = agentId == null || agentId.isBlank() ? AGENT_ENTRY : agentId;
        this.channel = channel;
        this.page = page;
        this.userState = userState;
        this.clarifyRetry = clarifyRetry;
        this.principal = principal == null ? PrincipalState.anonymous(channel) : principal;
        this.lineage = lineage;
    }

    public String traceId() {
        return traceId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    /**
     * 租户 / 空间标识，来自渠道网关注入的 {@code X-Space-ID}（FP-65）。
     *
     * <p>它必须参与一级出口缓存键：不参与的话，两个租户问同一句话会共用一条缓存记录，
     * 而两边的可用能力、限额与话术都可能不同。这类串味在测试环境几乎不可能复现——
     * 单租户跑起来一切正常。
     */
    public String spaceId() {
        return spaceId;
    }

    /**
     * 本请求所属 Agent（架构草案阶段 1）。
     *
     * <p>由服务端配置写入，不采信客户端。必须参与决策缓存键与会话作用域键：
     * 两个 Agent 问同一句话不得共用一条缓存或一把会话锁。
     */
    public String agentId() {
        return agentId;
    }

    public String channel() {
        return channel;
    }

    public String page() {
        return page;
    }

    public String userState() {
        return userState;
    }

    public boolean clarifyRetry() {
        return clarifyRetry;
    }

    public PrincipalState principal() {
        return principal;
    }

    public InvocationLineage lineage() {
        return lineage;
    }

    private static PrincipalState defaultPrincipal(String userId, String channel) {
        return userId == null || userId.isBlank()
                ? PrincipalState.anonymous(channel)
                : new PrincipalState(userId, true, "AUTHENTICATED", channel);
    }

    /**
     * 记一次网关往返。
     *
     * @param purpose 用途标识（embedding / arbitration / rerank / agent-tools）
     * @return 记录后的累计次数
     */
    public int recordGatewayRoundTrip(String purpose) {
        synchronized (gatewayCalls) {
            gatewayCalls.add(purpose);
        }
        return gatewayRoundTrips.incrementAndGet();
    }

    public int gatewayRoundTrips() {
        return gatewayRoundTrips.get();
    }

    public List<String> gatewayCalls() {
        synchronized (gatewayCalls) {
            return List.copyOf(gatewayCalls);
        }
    }

    /** Records a redacted module exchange for the per-request operations view. */
    public void recordModuleStep(RuntimeModuleStep step) {
        synchronized (moduleSteps) {
            moduleSteps.add(step);
        }
    }

    public List<RuntimeModuleStep> moduleSteps() {
        synchronized (moduleSteps) {
            return List.copyOf(moduleSteps);
        }
    }
}
