package com.huawei.finance.fastpath.cache;

import com.huawei.finance.common.context.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一级出口缓存的键（v0.7 §3.3 高频直出）。
 *
 * <p>键必须包含渠道、页面、用户状态与**全部影响结论的版本号**。少一个维度，
 * 缓存就会跨维度串味：换了渠道拿到别的渠道的出口，或者改了资产还在用旧结论。
 *
 * <p>用户状态维度受配置白名单约束（§3.3：M1 冻结枚举，禁止无边界塞入全量画像）。
 * 塞入全量画像的后果不是不安全，而是命中率归零——每个用户每次的键都不一样，
 * 缓存层白白增加一次 Redis 往返。
 */
public final class DecisionCacheKey {

    private static final String PREFIX = "huawei-finance-agent:route-decision:v3:";

    private DecisionCacheKey() {
    }

    /**
     * @param ctx                请求上下文
     * @param normalizedQuery    归一化查询
     * @param assetVersion       资产版本（含内容摘要）
     * @param embeddingModel     向量模型标识
     * @param instructionVersion 指令模板版本
     * @param promptVersion      仲裁提示词版本
     * @param userStateDimensions 参与键的用户状态维度白名单
     * @param userStateValues    用户状态取值
     */
    public static String of(RequestContext ctx,
                            String normalizedQuery,
                            String assetVersion,
                            String embeddingModel,
                            String instructionVersion,
                            String promptVersion,
                            List<String> userStateDimensions,
                            Map<String, String> userStateValues) {

        // 用 TreeMap 保证维度顺序稳定：顺序一变，同样的状态会算出不同的键，
        // 表现为缓存命中率无故下降，且极难定位
        TreeMap<String, String> state = new TreeMap<>();
        for (String dim : userStateDimensions) {
            state.put(dim, userStateValues.getOrDefault(dim, ""));
        }

        String raw = String.join("|",
                // 租户在最前面。两个租户问同一句话必须算出不同的键——可用能力、限额与话术
                // 都可能不同，而这类串味在单租户的测试环境里不可能复现（FP-65）
                nullSafe(ctx.spaceId()),
                // Agent 维紧随其后（架构草案阶段 1）：两个 Agent 同句不得共键
                nullSafe(ctx.agentId()),
                nullSafe(ctx.channel()),
                nullSafe(ctx.page()),
                state.toString(),
                assetVersion,
                embeddingModel,
                instructionVersion,
                promptVersion,
                normalizedQuery);

        return PREFIX + sha256(raw);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
