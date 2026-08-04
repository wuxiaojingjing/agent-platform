package com.huawei.finance.product.mobilebanking.console;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 控制台开关。 */
@ConfigurationProperties(prefix = "huawei.finance.mobile-banking.console")
public class ConsoleProperties {

    /** 控制台接口总开关。关掉之后连只读接口都不注册。 */
    private boolean enabled = true;

    /**
     * 是否允许经控制台改资产。
     *
     * <p><b>默认关。</b>资产归 Git 管，改动要过 MR 评审与 CI 的 AssetLint；页面直写绕开的
     * 正是这两道。开着它的正当场景是本地联调与演示环境——那里没有评审，快比稳重要。
     *
     * <p>它没有身份校验：这一层的鉴权本就该由行内网关做，在这里再写一套只会给人
     * 「已经有鉴权了」的错觉。所以生产上把它关着，是唯一可靠的保护。
     */
    private boolean writeEnabled = false;

    /** 页面展示名，不由前端自行拼接产品身份。 */
    private String displayName = "手机银行助手";

    /** 本地联调默认用户；生产租户信息仍由渠道网关请求头覆盖。 */
    private String defaultUserId = "u-console";

    private String defaultSpaceId = "default";

    private String defaultChannel = "WEB";

    private List<String> channels = List.of("WEB", "MOBILE_BANK", "WECHAT");

    private String sessionPrefix = "console";

    private String defaultPage = "home";

    private List<String> pageOptions = List.of("home", "account", "transfer");

    private String defaultUserState = "LOGGED_IN";

    private List<String> userStateOptions = List.of("LOGGED_IN", "GUEST");

    private List<String> exampleQueries = List.of(
            "查一下我的余额",
            "帮我转两千给张三",
            "先查余额，不足就别转");

    private Duration refreshInterval = Duration.ofSeconds(5);

    private boolean autoRefreshEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDefaultUserId() {
        return defaultUserId;
    }

    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    public String getDefaultSpaceId() {
        return defaultSpaceId;
    }

    public void setDefaultSpaceId(String defaultSpaceId) {
        this.defaultSpaceId = defaultSpaceId;
    }

    public String getDefaultChannel() {
        return defaultChannel;
    }

    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = defaultChannel;
    }

    public List<String> getChannels() {
        return channels;
    }

    public void setChannels(List<String> channels) {
        this.channels = List.copyOf(channels);
    }

    public String getSessionPrefix() {
        return sessionPrefix;
    }

    public void setSessionPrefix(String sessionPrefix) {
        this.sessionPrefix = sessionPrefix;
    }

    public String getDefaultPage() {
        return defaultPage;
    }

    public void setDefaultPage(String defaultPage) {
        this.defaultPage = defaultPage;
    }

    public List<String> getPageOptions() {
        return pageOptions;
    }

    public void setPageOptions(List<String> pageOptions) {
        this.pageOptions = List.copyOf(pageOptions);
    }

    public String getDefaultUserState() {
        return defaultUserState;
    }

    public void setDefaultUserState(String defaultUserState) {
        this.defaultUserState = defaultUserState;
    }

    public List<String> getUserStateOptions() {
        return userStateOptions;
    }

    public void setUserStateOptions(List<String> userStateOptions) {
        this.userStateOptions = List.copyOf(userStateOptions);
    }

    public List<String> getExampleQueries() {
        return exampleQueries;
    }

    public void setExampleQueries(List<String> exampleQueries) {
        this.exampleQueries = List.copyOf(exampleQueries);
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public boolean isAutoRefreshEnabled() {
        return autoRefreshEnabled;
    }

    public void setAutoRefreshEnabled(boolean autoRefreshEnabled) {
        this.autoRefreshEnabled = autoRefreshEnabled;
    }
}
