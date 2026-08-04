package com.huawei.finance.slowpath;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.ScopeKeys;
import com.openjiuwen.harness.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按 Agent / 会话复用的 DeepAgent Workspace 根目录。
 *
 * <p>OJ 构造 {@code DeepAgent} 必须带 Workspace，但权威计划在 Postgres（ADR-004）。
 * 架构草案阶段 1：目录为 {@code {root}/{agentId}/{sessionId}}，避免多 Agent 共目录
 * 让 FP-66 亲和判定失去意义。
 */
final class ProcessScopedWorkspace {

    private static final ConcurrentMap<String, Workspace> HOLDERS = new ConcurrentHashMap<>();

    private ProcessScopedWorkspace() {
    }

    static Workspace get(String configuredPath, String agentId, String sessionId) {
        String agent = ScopeKeys.segment(agentId, RequestContext.AGENT_ENTRY);
        String session = ScopeKeys.segment(sessionId, "nosession");
        String key = agent + "/" + session;
        return HOLDERS.computeIfAbsent(key, k -> create(configuredPath, agent, session));
    }

    /** 兼容旧调用：无会话时落到 {@code nosession} 子目录。 */
    static Workspace get(String configuredPath) {
        return get(configuredPath, RequestContext.AGENT_ENTRY, "nosession");
    }

    /** 测试用：清掉缓存，避免路径配置互相污染。 */
    static void resetForTests() {
        HOLDERS.clear();
    }

    private static Workspace create(String configuredPath, String agent, String session) {
        Path root = resolveRoot(configuredPath).resolve(agent).resolve(session);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建慢路径 Workspace 目录：" + root, e);
        }
        return Workspace.builder().rootPath(root.toAbsolutePath().toString()).build();
    }

    private static Path resolveRoot(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "agent-platform-slowpath-workspace");
    }
}
