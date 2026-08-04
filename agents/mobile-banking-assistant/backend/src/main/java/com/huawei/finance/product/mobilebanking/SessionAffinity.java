package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.ScopeKeys;
import com.huawei.finance.obs.AgentMetrics;
import com.huawei.finance.runtime.spi.SessionAffinityPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FP-66 实例亲和（ADR-004）：在办计划期间把会话粘在同一应用实例。
 *
 * <p>DeepAgent Workspace 是进程本地状态。多实例下若续办落到非 owner，harness 读不到
 * 磁盘草稿——应用内第二道闸拒绝续办，而不是静默半残跑下去。部署侧仍应对 chat 入口
 * 按 sessionId 做粘滞或一致性哈希。
 *
 * <p>键带 Agent + 租户前缀（架构草案阶段 1）。
 */
@Component
public class SessionAffinity implements SessionAffinityPort {

    private static final Logger log = LoggerFactory.getLogger(SessionAffinity.class);

    /** 与在办计划生命周期同量级；计划收口后可提前 {@link #release}。 */
    private static final Duration TTL = Duration.ofHours(2);

    private final RedissonClient redisson;
    private final MeterRegistry meterRegistry;
    private final String instanceId;

    public SessionAffinity(RedissonClient redisson, MeterRegistry meterRegistry,
                           @Value("${huawei.finance.agent.instance-id:}") String configuredId) {
        this.redisson = redisson;
        this.meterRegistry = meterRegistry;
        this.instanceId = resolveInstanceId(configuredId);
        log.info("实例亲和本机 identity={}", instanceId);
    }

    public String instanceId() {
        return instanceId;
    }

    public SessionAffinityPort.Outcome claim(RequestContext ctx) {
        return claim(ctx.agentId(), ctx.spaceId(), ctx.sessionId());
    }

    /**
     * 声明本实例拥有该会话。已是本实例或尚无主 → 成功；已有其他主 → mismatch。
     */
    public SessionAffinityPort.Outcome claim(String agentId, String spaceId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionAffinityPort.Outcome.OK;
        }
        RBucket<String> bucket = redisson.getBucket(ScopeKeys.sessionAffinity(agentId, spaceId, sessionId));
        String existing = bucket.get();
        if (existing == null) {
            if (bucket.setIfAbsent(instanceId, TTL)) {
                return SessionAffinityPort.Outcome.OK;
            }
            existing = bucket.get();
        }
        if (instanceId.equals(existing)) {
            bucket.expire(TTL);
            return SessionAffinityPort.Outcome.OK;
        }
        recordMismatch(sessionId, existing);
        return SessionAffinityPort.Outcome.MISMATCH;
    }

    public SessionAffinityPort.Outcome assertOwner(RequestContext ctx) {
        return assertOwner(ctx.agentId(), ctx.spaceId(), ctx.sessionId());
    }

    /**
     * 续办前校验：无绑定视为尚未声明（放行并尝试 claim）；绑了别人则 mismatch。
     */
    public SessionAffinityPort.Outcome assertOwner(String agentId, String spaceId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return SessionAffinityPort.Outcome.OK;
        }
        RBucket<String> bucket = redisson.getBucket(ScopeKeys.sessionAffinity(agentId, spaceId, sessionId));
        String existing = bucket.get();
        if (existing == null) {
            return claim(agentId, spaceId, sessionId);
        }
        if (instanceId.equals(existing)) {
            bucket.expire(TTL);
            return SessionAffinityPort.Outcome.OK;
        }
        recordMismatch(sessionId, existing);
        return SessionAffinityPort.Outcome.MISMATCH;
    }

    public void release(RequestContext ctx) {
        release(ctx.agentId(), ctx.spaceId(), ctx.sessionId());
    }

    /** 计划收口或作废时释放，避免会话长期粘死。 */
    public void release(String agentId, String spaceId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        RBucket<String> bucket = redisson.getBucket(ScopeKeys.sessionAffinity(agentId, spaceId, sessionId));
        String existing = bucket.get();
        if (instanceId.equals(existing)) {
            bucket.delete();
        }
    }

    private void recordMismatch(String sessionId, String owner) {
        log.warn("会话亲和错实例 session={} owner={} local={}（FP-66 / ADR-004）",
                sessionId, owner, instanceId);
        meterRegistry.counter(AgentMetrics.SESSION_AFFINITY_MISMATCH).increment();
    }

    private static String resolveInstanceId(String configuredId) {
        if (configuredId != null && !configuredId.isBlank()) {
            return configuredId.trim();
        }
        String host = Objects.requireNonNullElse(System.getenv("HOSTNAME"), "local");
        return host + "-" + ProcessHandle.current().pid();
    }

}

