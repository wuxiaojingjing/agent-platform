package com.huawei.finance.product.mobilebanking.console;

import com.huawei.finance.registry.asset.AssetStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 控制台装配。
 *
 * <p>整块可关：{@code huawei.finance.mobile-banking.console.enabled=false} 时连只读接口都不注册。行内如果自建运营台，
 * 这一整套就是多余的攻击面——留着一组能读出阈值与用户原话的接口，只因为「反正没人调」，
 * 是最典型的那种事后才被发现的口子。
 *
 * <p>{@link EngineRegistry} 与 {@link RecentDecisions} 不在这里：它们已经在主链路上，
 * 关掉控制台不该把请求处理一起关掉。
 */
@Configuration
@EnableConfigurationProperties(ConsoleProperties.class)
@ConditionalOnProperty(prefix = "huawei.finance.mobile-banking.console", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ConsoleConfiguration {

    @Bean
    public AssetEditor assetEditor(AssetStore store) {
        return new AssetEditor(store);
    }

    @Bean
    public PromptOptimizationView promptOptimizationView(AssetStore store) {
        return new PromptOptimizationView(store);
    }
}
