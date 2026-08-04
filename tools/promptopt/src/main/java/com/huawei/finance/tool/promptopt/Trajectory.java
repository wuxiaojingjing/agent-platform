package com.huawei.finance.agent.promptopt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条冻结的仲裁轨迹。
 *
 * <p>它是「那一次真跑时模型看到的全部输入」的逐字快照。{@code userPrompt} 不是这里拼出来的，
 * 而是在 {@code TrajectoryCaptureTest} 里从网关调用上**原样录下来**的——录的就是
 * {@code ChatRequest.userPrompt()}。
 *
 * <p>为什么非要逐字录、不肯在这里重渲染一遍：优化器要是对着一份自己拼的、格式相近的 prompt 调，
 * 那它调出来的提示词是对那份假 prompt 有效；上线后模型看到的是另一份，
 * 中间差一个字段顺序或一个换行就足以让整轮优化白做，而这种失败**没有任何报错**，
 * 只是效果不如预期。
 *
 * <p>为什么要冻结而不是每轮重跑真召回：一是贵且要 OpenSearch 在，二是没法归因——
 * 这一版变好了，是提示词的功劳还是召回这次刚好抖出了更好的候选？
 * 优化循环里这个问题必须无条件可答，否则就是在噪声上爬坡。
 *
 * <p>代价要说清楚：**冻结意味着优化器永远看不到召回的变化**。因此资产改过之后必须重录轨迹，
 * 否则优化的是一个已经不存在的召回态。这条写进了 {@link #assetVersion()} 里，
 * 优化器启动时会拿它与当前资产版本比对。
 *
 * @param caseId       对应种子集用例 id
 * @param query        用户原话，只用于报告可读性，不参与打分
 * @param userPrompt   逐字录下的 user 段
 * @param assetVersion 录制时的资产版本。与当前资产不一致说明轨迹过期
 * @param truth        真值
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Trajectory(
        String caseId,
        String query,
        String userPrompt,
        String assetVersion,
        Truth truth) {

    /**
     * 真值。
     *
     * <p>字段可为 null，表示这一项不参与打分——与种子集「未写的不校验」同一条约定。
     * 让优化器去满足一个没人给过的期望，它只会去满足自己的猜测。
     *
     * @param capability 真值能力；**null 表示「不该选任何能力」**，这与「不校验」不同，
     *                   由 {@link #capabilityDeclared} 区分
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Truth(
            String decision,
            String reasonCode,
            String capability,
            boolean capabilityDeclared,
            List<String> missingSlots,
            Map<String, String> slots) {

        public Truth {
            slots = slots == null ? Map.of() : new LinkedHashMap<>(slots);
        }
    }
}
