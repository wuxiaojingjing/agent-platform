package com.huawei.finance.fastpath.recall;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.PlanResolution;
import com.huawei.finance.contracts.model.SubIntent;
import com.huawei.finance.registry.asset.AssetBundle;
import com.huawei.finance.registry.asset.FusionConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把一句多意图的话切成若干件事（FP-18 / S4 场景 3）。
 *
 * <p>纯规则、零模型调用：拆解结果要在同步链路上立刻可用，不能再付一次仲裁往返。
 * 真正需要模型的规划留给 B 线的 {@code SlowPathPlanner}，那时拆解结果作为规划的起点，
 * 而不是替代它。
 *
 * <p>能力识别复用 {@link RuleRecall}：对每个片段单独跑一次关键词覆盖度。它同样是纯内存的，
 * 而且与整句召回用的是同一份词表，不会出现「整句认得出、片段认不出」之外的第二套口径。
 *
 * <p><b>拆不出来就不给计划。</b>{@link #split} 返回 {@link Optional#empty()} 而不是一份长度
 * 为 1 的计划，调用方据此退回今天的行为（一句「请您逐项办理」）。多任务检测的信号来自词表与
 * 召回证据，切分依据只有标点与连词，两者本就不可能永远一致——「查一下余额和信用卡账单」检测得
 * 出多意图，却因为「和」不在连词表里而切不开。这种情况下宁可少说，不能把整句话当成一件事的
 * 描述念给用户听。
 */
public class IntentSplitter {

    /** 分句标点。顿号不在内：「余额、账单」更像一个并列名词短语，不是两件事。 */
    private static final String CLAUSE_PUNCTUATION = "，,；;。！!？?";

    /** 片段短于此长度视为切碎了的噪声，并回前一段。 */
    private static final int MIN_SEGMENT_LENGTH = 2;

    /** 否定词。省略式识别只在它们后面找动词头，见 {@link #ellipticalCapability}。 */
    private static final List<String> NEGATIONS = List.of("别", "不要", "就不", "不用", "甭", "勿");

    private final AssetBundle bundle;
    private final RuleRecall ruleRecall;

    public IntentSplitter(AssetBundle bundle, RuleRecall ruleRecall) {
        this.bundle = bundle;
        this.ruleRecall = ruleRecall;
    }

    /**
     * @param normalizedQuery 归一化后的原话。用归一化而非去停用词的检索串：切分要保留「再」
     *                        「然后」这些连词，而它们正是停用词表里的常客
     * @return 至少两件事时给出计划，否则 {@link Optional#empty()}
     */
    public Optional<IntentPlan> split(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        FusionConfig.MultiTask config = bundle.fusion().getMultiTask();

        List<Segment> segments = segment(normalizedQuery, config);
        if (segments.size() < 2) {
            return Optional.empty();
        }

        List<Draft> drafts = new ArrayList<>();
        for (Segment segment : segments) {
            drafts.add(new Draft(segment, resolve(segment.text())));
        }

        recoverElided(drafts);
        attachConditions(drafts);
        attachCrossClauseResultRules(drafts);
        List<Draft> tasks = normalizeExplicitOrder(
                drafts.stream().filter(d -> !d.absorbed).toList());
        if (tasks.size() < 2) {
            // 条件从句被并进前一件事之后只剩一件——「如果余额不足就别转」正属此类，
            // 它是一件事加一个条件，不是两件事
            return Optional.empty();
        }

        List<SubIntent> items = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Draft draft = tasks.get(i);
            Enums.IntentRelation relation = relationOf(i, draft, config);
            items.add(new SubIntent(i, draft.segment.text(), draft.capabilityId,
                    summaryOf(draft), relation,
                    relation == Enums.IntentRelation.CONDITIONAL ? draft.condition : null,
                    draft.resolution));
        }
        return Optional.of(new IntentPlan(normalizedQuery, items, IntentPlan.Source.RULE));
    }

    /**
     * 按标点与连词切段，并记住每段前面挂的是哪个连词。
     *
     * <p>连词归属**后**一段而不是前一段：「查余额，再转 1000」里的「再」说的是转账要在查询之后，
     * 与查询本身无关。挂错一侧，第一件事会被判成依赖前序，而它根本没有前序。
     */
    private static List<Segment> segment(String query, FusionConfig.MultiTask config) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String pendingMarker = null;

        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (CLAUSE_PUNCTUATION.indexOf(c) >= 0) {
                pendingMarker = flush(segments, current, pendingMarker);
                i++;
                continue;
            }
            String marker = markerAt(query, i, config.getConjunctions());
            if (marker != null && current.length() >= MIN_SEGMENT_LENGTH) {
                pendingMarker = flush(segments, current, pendingMarker);
                pendingMarker = marker;
                i += marker.length();
                continue;
            }
            if (marker != null && current.isEmpty() && pendingMarker == null) {
                // 整句以连词开头（「再查一下余额」）。这里没有前序可依赖，
                // 记下连词但不切段——切了会产出一个空的第一段
                pendingMarker = marker;
                i += marker.length();
                continue;
            }
            current.append(c);
            i++;
        }
        flush(segments, current, pendingMarker);
        return segments;
    }

    /**
     * 收尾当前片段。
     *
     * @return 下一段应当携带的连词（本次已消费，恒为 null）
     */
    private static String flush(List<Segment> segments, StringBuilder current, String marker) {
        String text = current.toString().trim();
        current.setLength(0);
        if (text.length() >= MIN_SEGMENT_LENGTH) {
            segments.add(new Segment(text, marker));
        } else if (!text.isEmpty() && !segments.isEmpty()) {
            // 切碎的尾巴并回上一段，别让它冒充一件事
            Segment last = segments.remove(segments.size() - 1);
            segments.add(new Segment(last.text() + text, last.marker()));
        }
        return null;
    }

    private static String markerAt(String query, int index, List<String> markers) {
        for (String marker : markers) {
            if (!marker.isEmpty() && query.startsWith(marker, index)) {
                return marker;
            }
        }
        return null;
    }

    /**
     * 把条件从句里被省略掉的那件事补回来。
     *
     * <p><b>只补没人说过的。</b>「查余额，再给老徐转 1000；不足就别转」里转账已经正面说过了，
     * 这句就是对它的限定，该并进去；而「先查余额，不足就别转」里转账从没出现过，
     * 不补就永远丢了。判据是这张卡有没有被别的分句认领。
     *
     * <p>补出来的那件事，片段正文同时就是它自己的条件：「不足就别转」既是「转账」，
     * 也是它的前提。条件原文带走，成立与否交给下游的条件评估器。
     */
    private void recoverElided(List<Draft> drafts) {
        for (Draft draft : drafts) {
            if (draft.capabilityId != null || !mentionsCondition(draft.segment.text())) {
                continue;
            }
            String elided = ellipticalCapability(draft.segment.text());
            if (elided == null) {
                continue;
            }
            Draft claimant = claimantElsewhere(drafts, draft, elided);
            if (claimant != null) {
                // “给老徐转 1000；不足就别转”里，正向子句只有单字“转”，规则召回本身
                // 不足以锁定能力；后面的否定条件却把这个动作唯一限定为转账。识别结果应当
                // 回填到真正待执行的正向子句，条件句随后再并入它，不能留下未解析步骤。
                if (claimant.capabilityId == null) {
                    claimant.capabilityId = elided;
                    claimant.resolution = new PlanResolution(PlanResolution.Strength.LOCKED,
                            1.0, 1.0, List.of(elided), List.of("condition-action-head:unique"));
                }
                claimant.condition = draft.segment.text();
                draft.absorbed = true;
                continue;
            }
            draft.capabilityId = elided;
            draft.condition = draft.segment.text();
            draft.resolution = new PlanResolution(PlanResolution.Strength.LOCKED,
                    1.0, 1.0, List.of(elided), List.of("elliptical:unique-head"));
        }
    }

    /**
     * 别的分句是不是已经在说这件事了。
     *
     * <p>不能只看它有没有被成功识别：「给老徐转 1000」也认不出能力（词表里是「转账」「转钱」，
     * 都对不上），可它明明就是那笔转账。所以判据放宽到**提到了这张卡的动词头**，
     * 与省略式识别用的是同一份口径。宁可错过一次补全，也不能把同一笔转账拆成两条——
     * 那意味着用户会被转两次钱。
     */
    private Draft claimantElsewhere(List<Draft> drafts, Draft self, String capabilityId) {
        for (Draft other : drafts) {
            if (other == self) {
                continue;
            }
            if (capabilityId.equals(other.capabilityId) || mentionsHeadOf(other.segment.text(), capabilityId)) {
                return other;
            }
        }
        return null;
    }

    private boolean mentionsHeadOf(String text, String capabilityId) {
        for (int i = 0; i < text.length(); i++) {
            if (capabilityId.equals(soleOwnerOfHead(text.charAt(i)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 条件从句里那个只以否定式露过头的动词指向哪张卡：「先查余额，不足就<b>别转</b>」。
     *
     * <p>转账从没被正面说出口过，只在否定式里露了一个动词头，词表匹配自然是零分。
     * 但用户确实提了两件事，识别不出来的话，澄清会退化成一句「您提到了多件事，先办哪一件」
     * 却列不出是哪几件——用户没法选，计划也开不起来，比不问还糟。
     *
     * <p>只认「否定词紧跟关键词首字」，且该首字在全部可召回能力里唯一。撞车就放弃：
     * 与其把「别查」认到两张卡里的某一张上，不如退回并入前一件事的老路。
     *
     * <p>这条通路只对条件从句开放。整句召回不走这里——单字匹配放到主链路上，
     * 「不转发这条消息」也会被认成转账。
     */
    private String ellipticalCapability(String text) {
        String found = null;
        for (String negation : NEGATIONS) {
            int at = text.indexOf(negation);
            while (at >= 0) {
                int headIndex = at + negation.length();
                if (headIndex < text.length()) {
                    String owner = soleOwnerOfHead(text.charAt(headIndex));
                    if (owner != null) {
                        if (found != null && !found.equals(owner)) {
                            return null;
                        }
                        found = owner;
                    }
                }
                at = text.indexOf(negation, at + 1);
            }
        }
        return found;
    }

    /** 以该字开头的关键词若只属于一张卡，返回那张卡；无人认领或多卡共用都返回 null。 */
    private String soleOwnerOfHead(char head) {
        String owner = null;
        for (CapabilityCard card : bundle.recallableCapabilities()) {
            if (!navigationAllowed(String.valueOf(head), card.capabilityId())) {
                continue;
            }
            for (String keyword : card.keywords()) {
                if (keyword.isEmpty() || keyword.charAt(0) != head) {
                    continue;
                }
                if (owner != null && !owner.equals(card.capabilityId())) {
                    return null;
                }
                owner = card.capabilityId();
                break;
            }
        }
        return owner;
    }

    /**
     * 把「不足就别转」这类纯条件从句并进它所修饰的那件事。
     *
     * <p>判据是**认不出能力**：能认出能力的条件句（「如果余额不够就转 500」）本身就是一件待办，
     * 认不出的才是对邻近那件事的限定。就近原则取前序，句首的条件句取后继。
     */
    private void attachConditions(List<Draft> drafts) {
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            if (draft.absorbed || draft.capabilityId != null
                    || !mentionsCondition(draft.segment.text())) {
                continue;
            }
            Draft host = hostFor(drafts, i);
            if (host == null) {
                continue;
            }
            host.condition = draft.segment.text();
            draft.absorbed = true;
        }
    }

    /** Handles “A 没发生，就执行 B” when punctuation split the configured marker across clauses. */
    private static void attachCrossClauseResultRules(List<Draft> drafts) {
        for (int i = 1; i < drafts.size(); i++) {
            Draft current = drafts.get(i);
            Draft previous = drafts.get(i - 1);
            if (current.absorbed || previous.absorbed || current.capabilityId == null
                    || current.condition != null || !current.segment.text().startsWith("就")) {
                continue;
            }
            String premise = previous.segment.text();
            if (premise.contains("没到账") || premise.contains("未到账")
                    || premise.contains("不足") || premise.contains("不够")
                    || premise.contains("失败")) {
                current.condition = premise;
            }
        }
    }

    private static Draft hostFor(List<Draft> drafts, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (!drafts.get(i).absorbed) {
                return drafts.get(i);
            }
        }
        for (int i = index + 1; i < drafts.size(); i++) {
            if (!drafts.get(i).absorbed) {
                return drafts.get(i);
            }
        }
        return null;
    }

    private boolean mentionsCondition(String text) {
        for (String marker : bundle.fusion().getMultiTask().getConditionals()) {
            if (!marker.isEmpty() && text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private Enums.IntentRelation relationOf(int index, Draft draft, FusionConfig.MultiTask config) {
        if (index == 0) {
            // 第一件事没有前序。它身上挂着的条件此处只能丢——不过条件从句会优先并到前序，
            // 只有整句以条件开头时才走到这里，而那时后继才是被限定的那件事
            return Enums.IntentRelation.PARALLEL;
        }
        if (draft.condition != null) {
            return Enums.IntentRelation.CONDITIONAL;
        }
        String marker = draft.segment.marker();
        boolean sequential = marker != null && config.getSequentialMarkers().contains(marker);
        return sequential ? Enums.IntentRelation.SEQUENTIAL : Enums.IntentRelation.PARALLEL;
    }

    /**
     * “先”是用户给出的执行顺序，不受分句出现位置限制。
     * 例如“帮我转账，先查余额，不足就别转”中，余额查询必须移动到转账前面。
     */
    private static List<Draft> normalizeExplicitOrder(List<Draft> drafts) {
        if (drafts.size() < 2) return drafts;
        List<Draft> explicitFirst = drafts.stream()
                .filter(draft -> draft.segment.text().stripLeading().startsWith("先"))
                .toList();
        if (explicitFirst.isEmpty() || explicitFirst.getFirst() == drafts.getFirst()) {
            return drafts;
        }
        List<Draft> ordered = new ArrayList<>(drafts.size());
        ordered.addAll(explicitFirst);
        drafts.stream().filter(draft -> !explicitFirst.contains(draft)).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /** 对单个子句做稳定排序，并把 Planner 的允许范围固化进计划。 */
    private Resolution resolve(String segmentText) {
        RuleRecall.Result recalled = ruleRecall.recall(segmentText);
        List<Map.Entry<String, Double>> ranked = recalled.scores().entrySet().stream()
                .filter(entry -> navigationAllowed(segmentText, entry.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .toList();
        double topScore = ranked.isEmpty() ? 0.0 : ranked.get(0).getValue();
        double secondScore = ranked.size() < 2 ? 0.0 : ranked.get(1).getValue();
        double margin = topScore - secondScore;
        FusionConfig.Thresholds thresholds = bundle.fusion().getThresholds();
        FusionConfig.Planning planning = bundle.fusion().getPlanning();

        PlanResolution.Strength strength;
        String selected = null;
        if (!ranked.isEmpty() && topScore >= thresholds.getTop1Min()
                && margin >= thresholds.getMarginMin()) {
            strength = PlanResolution.Strength.LOCKED;
            selected = ranked.get(0).getKey();
        } else if (!ranked.isEmpty() && topScore > planning.getPreferredMin()) {
            strength = PlanResolution.Strength.PREFERRED;
            selected = ranked.get(0).getKey();
        } else {
            strength = PlanResolution.Strength.UNRESOLVED;
        }

        List<String> candidates = ranked.stream()
                .limit(planning.getMaxCandidatesPerStep())
                .map(Map.Entry::getKey)
                .toList();
        List<String> evidence = ranked.isEmpty()
                ? List.of() : recalled.evidenceOf(ranked.get(0).getKey());
        return new Resolution(selected, new PlanResolution(
                strength, topScore, margin, candidates, evidence));
    }

    private boolean navigationAllowed(String segmentText, String capabilityId) {
        if (!capabilityId.startsWith("cap.nav.")) {
            return true;
        }
        return bundle.fusion().getPlanning().getNavigationMarkers().stream()
                .anyMatch(segmentText::contains);
    }

    /** 有能力卡就用卡的显示名，用户看到的是「转账」而不是自己刚说过的半句话。 */
    private String summaryOf(Draft draft) {
        if (draft.capabilityId != null) {
            CapabilityCard card = bundle.capability(draft.capabilityId);
            if (card != null && card.name() != null && !card.name().isBlank()) {
                return card.name();
            }
        }
        return draft.segment.text();
    }

    /**
     * @param text   片段正文，已剥掉引导连词
     * @param marker 引导本片段的连词，无则为 null
     */
    private record Segment(String text, String marker) {
    }

    /** 切分与识别的中间态。条件归并会改写它，所以不用 record。 */
    private static final class Draft {
        private final Segment segment;
        private String capabilityId;
        private PlanResolution resolution;
        private String condition;
        private boolean absorbed;

        private Draft(Segment segment, Resolution resolved) {
            this.segment = segment;
            this.capabilityId = resolved.capabilityId();
            this.resolution = resolved.resolution();
        }
    }

    private record Resolution(String capabilityId, PlanResolution resolution) {
    }
}
