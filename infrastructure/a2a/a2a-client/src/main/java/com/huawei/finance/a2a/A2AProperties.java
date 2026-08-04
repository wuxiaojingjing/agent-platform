package com.huawei.finance.a2a;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A2A 网关参数（架构草案 v0.2 §6.3）。
 *
 * <p>三个值都外置到配置，但**默认值是保守方向**:深度 3 是文档建议值，
 * 回传预留 500ms 让每一层都留出把结果传回去的时间——不留的话，
 * 最深那一层用完全部预算，回传路上必然超时,而上游看到的是「结果未知」。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.a2a")
public class A2AProperties {

    /** 委托深度上限。超限即 FATAL，不静默截断。 */
    private int maxDepth = 3;

    /** 单层委托本地上限，毫秒。逐层取 min 时的一项。 */
    private long localTimeoutMs = 8000;

    /** 每层为回传预留的时间，毫秒。从上游 deadline 里先扣掉。 */
    private long returnReserveMs = 500;

    /** 改投上限。§7.1:一次，不是遍历 Top-K。 */
    private int maxReroutes = 1;

    /**
     * 节点卡位置，Spring 资源模式。留空则扫描共享根 + {@code agents/<id>/assets}。
     *
     * <p>默认与 {@code huawei.finance.agent.registry.assets-path} 同源:资产在 Git 仓里，不打进 jar。
     * 换成 {@code classpath*:} 会让路由表静默空掉——
     * 表现是「26 个域都没交付」，而不是一个显式错误。
     */
    private String cardLocation;

    /**
     * 叶子能力卡位置，GOAL 关键词从这里读。留空则多根浅层 + nav 子目录。
     *
     * <p>与 {@link #cardLocation} 分开是因为两者读的是不同的卡：那边读 AGENT 父卡拿路由表，
     * 这边读 TOOL 叶子卡拿关键词。合成一个模式会把父卡也读进关键词表，
     * 于是 GOAL 可能落到一张不可执行的父卡上。
     */
    private String capabilityLocation;

    /**
     * 节点卡资源模式列表（D1 多根）。
     *
     * <p>显式配置了 {@code cardLocation} 时只返回那一项；否则对每个资产根拼
     * capabilities/agents 下 yaml 文件（glob）。
     */
    public List<String> resolveCardLocations() {
        if (cardLocation != null && !cardLocation.isBlank()) {
            return List.of(cardLocation);
        }
        List<String> patterns = new ArrayList<>();
        for (Path root : AgentAssetLocations.allAssetRoots()) {
            patterns.add(filePattern(root, "capabilities/agents/*.yaml"));
        }
        return List.copyOf(patterns);
    }

    /**
     * 叶子卡资源模式列表。
     *
     * <p>每个根：浅层 capabilities yaml 与 capabilities/nav 下 yaml
     *（不含 {@code agents/}，避免把父卡读进 GOAL 词表）。
     */
    public List<String> resolveCapabilityLocations() {
        if (capabilityLocation != null && !capabilityLocation.isBlank()) {
            return List.of(capabilityLocation);
        }
        List<String> patterns = new ArrayList<>();
        for (Path root : AgentAssetLocations.allAssetRoots()) {
            patterns.add(filePattern(root, "capabilities/*.yaml"));
            patterns.add(filePattern(root, "capabilities/nav/*.yaml"));
        }
        return List.copyOf(patterns);
    }

    /**
     * 兼容单 pattern API：显式配置原样返回；默认取多根中的第一项。
     *
     * <p>新代码应走 {@link #resolveCardLocations()}。
     */
    public String getCardLocation() {
        if (cardLocation != null && !cardLocation.isBlank()) {
            return cardLocation;
        }
        List<String> all = resolveCardLocations();
        return all.isEmpty() ? filePattern(AgentAssetLocations.requireAssets(), "capabilities/agents/*.yaml")
                : all.get(0);
    }

    public void setCardLocation(String cardLocation) {
        this.cardLocation = cardLocation;
    }

    public String getCapabilityLocation() {
        if (capabilityLocation != null && !capabilityLocation.isBlank()) {
            return capabilityLocation;
        }
        List<String> all = resolveCapabilityLocations();
        return all.isEmpty() ? filePattern(AgentAssetLocations.requireAssets(), "capabilities/*.yaml")
                : all.get(0);
    }

    public void setCapabilityLocation(String capabilityLocation) {
        this.capabilityLocation = capabilityLocation;
    }

    private static String filePattern(Path root, String relative) {
        return "file:" + root.resolve(relative);
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public long getLocalTimeoutMs() {
        return localTimeoutMs;
    }

    public void setLocalTimeoutMs(long localTimeoutMs) {
        this.localTimeoutMs = localTimeoutMs;
    }

    public long getReturnReserveMs() {
        return returnReserveMs;
    }

    public void setReturnReserveMs(long returnReserveMs) {
        this.returnReserveMs = returnReserveMs;
    }

    public int getMaxReroutes() {
        return maxReroutes;
    }

    public void setMaxReroutes(int maxReroutes) {
        this.maxReroutes = maxReroutes;
    }
}
