package com.huawei.finance.cache.redis;

import com.huawei.finance.intent.cache.DecisionCache;
import com.huawei.finance.registry.asset.AssetBundle;
import java.time.Duration;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;

/**
 * Redis 出口缓存装配。
 *
 * <p>{@code @AutoConfigureBefore} 让顺序成为**明写的**。{@code FastPathConfiguration} 也提供一个
 * {@code DecisionCache}（{@link DecisionCache#disabled()}，不缓存），两边都带
 * {@link ConditionalOnMissingBean}，谁先评估谁生效。
 *
 * <p>把这个标注去掉，眼下并不会坏——Boot 在没有顺序声明时按类名字母序排，
 * {@code com.huawei.finance.cache.redis.*} 恰好排在 {@code com.huawei.finance.fastpath.*} 前面，方向碰巧一致
 * （实测：去掉标注用例仍绿，改成 {@code @AutoConfigureAfter} 才变红）。
 * 但那是靠包名的字母序在支撑：把本模块的包改个名、或引擎那侧挪个位置，
 * 顺序就悄悄反过来，赢的变成「不缓存」。而那个故障没有报错也没有失败的用例，
 * 只有一条变差的时延曲线。所以顺序要明写，不能留给字母序。
 *
 * <p>{@code @ConditionalOnBean(RedissonClient.class)} 而不是无条件装配：本模块在 classpath 上
 * 只表示「打算用 Redis」，连接由使用方提供。没有 {@code RedissonClient} 时让位给引擎的
 * 不缓存默认值，比抛一个「缺 Bean」启动失败更符合缓存的地位——它是加速手段，不是数据源。
 */
/*
  写字符串而不是 FastPathConfiguration.class：本模块只依赖 intent-engine-api，
  intent-fastpath 不在编译期 classpath 上（这正是拆门面要换来的东西——一个适配器
  不该为实现一个 @Spi 就背上 hanlp 与 aviator）。`name` 属性收类名字符串，
  类不在场也能声明顺序，Boot 在运行期按名解析，解析不到就当这条顺序不存在。

  代价是丢了「类名写错编译不过」这层保护。而且这个代价比它看起来更贵：
  字符串写错时 Boot 解析不到，就退回类名字母序——而字母序今天恰好也是对的
  （com.huawei.finance.cache 排在 com.huawei.finance.fastpath 前），于是写错了也照样绿。
  ExtensionPointOverrideTest.RedisCacheWinsWhenNotOverridden 挡不住这一格，
  它自己的注释里就写明了「把标注整个删掉它仍然绿」，同一个理由。

  所以另立一条闸门验这个字符串本身解析得到类：
  ExtensionPointOverrideTest#autoConfigureBeforeTargetResolves。
  依赖方向（cache-redis 不再需要 fastpath 在编译期在场）由
  ModuleDependencyTest#intentEngineApiIsDependableAlone 钉住。
*/
@AutoConfiguration(after = RedissonClientAutoConfiguration.class)
@AutoConfigureBefore(name = "com.huawei.finance.fastpath.FastPathConfiguration")
public class RedisDecisionCacheConfiguration {

    /**
     * TTL 取启动时那份资产的配置，重载不会改它：TTL 是缓存介质的运行参数，
     * 而 Redisson 的过期是写入时定的，改了也只对新写入生效，跟着资产版本变只会让人误解。
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean(DecisionCache.class)
    public RedisDecisionCache redisDecisionCache(
            RedissonClient redisson,
            AssetBundle bundle,
            @Value("${huawei.finance.cache.decision.enabled:false}") boolean enabled) {
        RedisDecisionCache cache = new RedisDecisionCache(redisson,
                Duration.ofSeconds(bundle.fusion().getCache().getTtlSeconds()), enabled);
        if (!enabled) {
            cache.clear();
        }
        return cache;
    }
}
