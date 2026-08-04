package com.huawei.finance.fastpath.eval;

import java.util.List;

/**
 * 归因：这条判错，错在召回还是错在仲裁（FP-52 诊断）。
 *
 * <p>分开归因不是为了好看，是因为**两类问题的修法完全不同，且互相修不了对方**：
 *
 * <ul>
 *   <li>召回问题（真值能力压根没进候选）只能改资产——补话术样例、补关键词、补描述。
 *       提示词写得再好，模型也选不出一个它看不见的能力。</li>
 *   <li>仲裁问题（真值能力就在候选里，模型却选了别的或判错出口）只能改提示词或模型。
 *       往能力卡里堆更多样例，对这类问题一点用没有，只会顺带把别的召回搅浑。</li>
 * </ul>
 *
 * <p>不先归因就动手，最常见的结局是往能力卡里加词——因为那最容易——然后发现没用，
 * 再加更多词，最后资产被样例堆成一团，而真正的病在提示词里没人碰过。
 */
public record Culprit(Kind kind, String detail) {

    public enum Kind {
        /** 判对了，没有归因可言。 */
        NONE,
        /** 真值能力不在候选集里：召回的问题。 */
        RECALL,
        /** 真值能力在候选集里，但模型没选它、或出口/理由判错：仲裁的问题。 */
        ARBITRATION,
        /** 真值待业务给，不做归因——比归因到一个猜出来的真值上要好。 */
        TRUTH_PENDING
    }

    /**
     * @param truth      真值，null 表示待业务给
     * @param diffs      实测与真值的差异，空表示判对
     * @param candidates 送进仲裁的候选能力 ID（已按融合分降序）
     */
    public static Culprit of(EvalCase.Expect truth, List<String> diffs, List<String> candidates) {
        if (truth == null) {
            return new Culprit(Kind.TRUTH_PENDING, "真值待业务");
        }
        if (diffs.isEmpty()) {
            return new Culprit(Kind.NONE, "");
        }

        String want = truth.getCapability();
        if (want == null) {
            // 真值是「不该选任何能力」（越界、多意图）。这种判错必然是仲裁的判断问题：
            // 候选里有什么都不影响「不该执行」这个结论
            return new Culprit(Kind.ARBITRATION, "真值为不选任何能力，实测却选了或判错出口");
        }
        if (!candidates.contains(want)) {
            return new Culprit(Kind.RECALL,
                    "真值能力 " + want + " 未进候选，候选为 " + candidates);
        }
        return new Culprit(Kind.ARBITRATION,
                "真值能力 " + want + " 在候选第 " + (candidates.indexOf(want) + 1) + " 位，模型未据此判定");
    }
}
