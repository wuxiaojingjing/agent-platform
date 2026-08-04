package com.huawei.finance.nacos.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 判定一次配置推送里哪些键可以就地生效。
 *
 * <p>做成不依赖 Spring 的纯函数，是因为这条判定是整个模块唯一会「悄悄出错」的地方：
 * 判宽了会让一个其实没生效的改动被当成生效了，判窄了会让运维以为配置中心不好使。
 * 两种错都不会抛异常，只能靠用例钉。
 */
public final class HotReloadPolicy {

    /**
     * 无论白名单怎么配都不许热更新的前缀。
     *
     * <p>{@code huawei.finance.agent.nacos.} 是「去哪里取配置」本身，从取回来的配置里改它是循环；
     * {@code spring.} 底下绝大多数键（数据源、连接池、web 服务器）在启动时就被消费掉了，
     * 推下去改的只是 Environment 里的字符串，实际行为一点不变——这正是最坏的一种：
     * 看起来生效了。
     */
    private static final List<String> NEVER = List.of("huawei.finance.agent.nacos.", "spring.");

    private final List<String> refreshable;

    public HotReloadPolicy(List<String> refreshable) {
        this.refreshable = List.copyOf(refreshable);
    }

    /**
     * 比较新旧两份配置。
     *
     * @return 三类键：可应用的（带新值）、被策略拒绝的、白名单外只记录的
     */
    public Plan plan(Map<String, Object> before, Map<String, Object> after) {
        Set<String> touched = new TreeSet<>(before.keySet());
        touched.addAll(after.keySet());

        Map<String, Object> applied = new LinkedHashMap<>();
        Set<String> rejected = new TreeSet<>();
        Set<String> ignored = new TreeSet<>();

        for (String key : touched) {
            Object oldValue = before.get(key);
            Object newValue = after.get(key);
            if (java.util.Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (NEVER.stream().anyMatch(key::startsWith)) {
                rejected.add(key);
            } else if (refreshable.stream().anyMatch(key::startsWith)) {
                // 删掉的键要还原成「没有」，不能留着旧值：否则在 Nacos 上删一行等于没删
                applied.put(key, newValue);
            } else {
                ignored.add(key);
            }
        }
        return new Plan(applied, rejected, ignored);
    }

    /**
     * @param applied 键 → 新值，值为 {@code null} 表示这个键被删了
     */
    public record Plan(Map<String, Object> applied, Set<String> rejected, Set<String> ignored) {

        public boolean isEmpty() {
            return applied.isEmpty() && rejected.isEmpty() && ignored.isEmpty();
        }
    }
}
