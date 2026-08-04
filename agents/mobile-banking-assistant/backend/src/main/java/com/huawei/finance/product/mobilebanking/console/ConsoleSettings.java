package com.huawei.finance.product.mobilebanking.console;

import java.util.List;

/** 控制台启动参数。前端只消费此契约，不保留产品运行参数的本地副本。 */
public record ConsoleSettings(
        Agent agent,
        TenantDefaults tenantDefaults,
        Chat chat,
        Operations operations) {

    public static ConsoleSettings from(String agentId, ConsoleProperties properties) {
        return new ConsoleSettings(
                new Agent(agentId, properties.getDisplayName()),
                new TenantDefaults(
                        properties.getDefaultUserId(),
                        properties.getDefaultSpaceId(),
                        properties.getDefaultChannel(),
                        properties.getChannels()),
                new Chat(
                        properties.getSessionPrefix(),
                        properties.getDefaultPage(),
                        properties.getPageOptions(),
                        properties.getDefaultUserState(),
                        properties.getUserStateOptions(),
                        properties.getExampleQueries()),
                new Operations(
                        properties.getRefreshInterval().toMillis(),
                        properties.isAutoRefreshEnabled()));
    }

    public record Agent(String id, String displayName) {
    }

    public record TenantDefaults(
            String userId,
            String spaceId,
            String channel,
            List<String> channels) {
    }

    public record Chat(
            String sessionPrefix,
            String defaultPage,
            List<String> pages,
            String defaultUserState,
            List<String> userStates,
            List<String> exampleQueries) {
    }

    public record Operations(long refreshIntervalMillis, boolean autoRefreshEnabled) {
    }
}
