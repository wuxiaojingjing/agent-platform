package com.huawei.finance.slowpath;

import com.huawei.finance.intent.IntentPlanner;
import com.huawei.finance.intent.SlowPathProperties;

import com.huawei.finance.common.context.RequestContext;
import com.huawei.finance.common.context.RequestContextHolder;
import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.contracts.model.Enums;
import com.huawei.finance.contracts.model.IntentPlan;
import com.huawei.finance.contracts.model.SubIntent;
import com.openjiuwen.core.session.AgentSessionApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 OJ {@link com.openjiuwen.harness.deep_agent.DeepAgent} 的规划器（ADR-004）。
 *
 * <p>类名历史遗留（曾用裸 ReActAgent）。本类负责模型调用、提案折叠和调用失败回退；
 * 逐步骤候选、锁定、顺序与条件的最终校验统一由 Runtime 的 Grounding Policy 执行，业务
 * {@link IntentPlanner} 覆盖实现也不能绕过。
 */
public class ReActIntentPlanner implements IntentPlanner {

    private static final Logger log = LoggerFactory.getLogger(ReActIntentPlanner.class);

    private final SlowPathProperties props;

    public ReActIntentPlanner(SlowPathProperties props) {
        this.props = props;
    }

    @Override
    public Optional<IntentPlan> plan(String goal, List<CapabilityCard> candidates,
                                     IntentPlan ruleFallback) {
        Objects.requireNonNull(ruleFallback, "Slow Path Planner 必须接收规则步骤锚点");
        if (candidates.isEmpty()) {
            log.info("无候选能力，慢路径不规划，回退规则拆解 goalLength={}", length(goal));
            return Optional.of(ruleFallback);
        }

        List<TaskProposal> proposals;
        try {
            proposals = propose(plannerInput(goal, ruleFallback), candidates);
        } catch (RuntimeException e) {
            // 模型不可用不是拒绝用户的理由。这条回退与快路径「仲裁失败退规则」是同一条原则
            log.warn("慢路径规划失败，回退规则拆解 goalLength={} cause={}", length(goal), e.toString());
            return Optional.of(ruleFallback);
        }

        return Optional.of(toPlan(goal, proposals, ruleFallback));
    }

    /**
     * 跑一次 ReAct 循环拿提案。
     *
     * <p>单独一个方法是为了让「折叠与回退」这部分逻辑可以在不打模型的前提下被测到。
     * 真实规划质量归评测管，而回退分支恰恰是模型不参与的那条，用真模型反而测不稳。
     */
    protected List<TaskProposal> propose(String goal, List<CapabilityCard> candidates) {
        SlowPathPlanner planner = new SlowPathPlanner(
                candidates, props.getModel(), props.getMaxIterations(), props.getWorkspacePath());
        RequestContext ctx = RequestContextHolder.get();
        String agentId = ctx == null ? RequestContext.AGENT_ENTRY : ctx.agentId();
        String session = ctx == null ? "nosession" : ctx.sessionId();
        // 用 AgentSessionApi 而不是快路径那份 WorkflowSessionApi：跑的是 Agent 不是图，
        // 两者都实现 Session，但工作流会话带着图的节点状态，喂给 DeepAgent 没有意义
        return planner.plan(goal, new AgentSessionApi(sessionId()), agentId, session);
    }

    /**
     * 把提案列表折成计划。
     *
     * <p>这里保留三处解析期的保守处理：
     * <ol>
     *   <li>模型只给出一步时保留规则计划。多意图漏识别属于「复杂误判为简单」，
     *       代价是真的只办了其中一件；而多判一件的代价只是多问一句。</li>
     *   <li>规则认出的条件依赖要**带过来**。ReAct 规划器没有条件的概念，
     *       换上它以后如果「不足就别转」凭空消失，那是一次静默的安全回退。</li>
     *   <li>规则任一步有非空条件，而合并后对应步条件全部为空 → 整份退回 RULE。
     *       对齐只靠 capabilityId；对不齐时静默丢掉「不足就别转」比用规则计划更糟。</li>
     * </ol>
     */
    private static IntentPlan toPlan(String goal, List<TaskProposal> proposals,
                                     IntentPlan ruleFallback) {
        if (proposals.size() < 2) {
            log.info("慢路径只规划出 {} 步，保留规则拆解 goalLength={}", proposals.size(), length(goal));
            return ruleFallback;
        }

        List<SubIntent> items = new ArrayList<>();
        for (int i = 0; i < proposals.size(); i++) {
            TaskProposal proposal = proposals.get(i);
            CapabilityCard card = proposal.capability();
            SubIntent anchor = i < ruleFallback.items().size()
                    ? ruleFallback.items().get(i) : null;
            if (anchor == null) {
                return ruleFallback;
            }
            String condition = anchor.condition();

            if (condition != null && i == 0) {
                log.info("条件落在首步，无处依附，保留规则拆解 goalLength={} capability={}",
                        length(goal), card.capabilityId());
                return ruleFallback;
            }

            Enums.IntentRelation relation = anchor.relation();

            items.add(new SubIntent(i, anchor.text(),
                    card.capabilityId(), summaryOf(card), relation, condition,
                    anchor.resolution()));
        }

        if (ruleHasCondition(ruleFallback) && items.stream().noneMatch(ReActIntentPlanner::hasCondition)) {
            log.warn("规则计划含条件但规划合并后全部丢失，回退规则拆解 goalLength={}", length(goal));
            return ruleFallback;
        }

        return new IntentPlan(goal, items, IntentPlan.Source.PLANNER);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean ruleHasCondition(IntentPlan ruleFallback) {
        return ruleFallback.items().stream().anyMatch(ReActIntentPlanner::hasCondition);
    }

    private static boolean hasCondition(SubIntent item) {
        return item.condition() != null && !item.condition().isBlank();
    }

    /** 把逐步骤允许候选显式交给模型；候选并集本身表达不了哪张卡属于哪一步。 */
    private static String plannerInput(String goal, IntentPlan ruleFallback) {
        StringBuilder input = new StringBuilder();
        input.append("任务目标：").append(goal == null ? "" : goal).append('\n');
        input.append("步骤约束（必须逐条、按序各 propose 一次）：\n");
        for (SubIntent item : ruleFallback.items()) {
            input.append(item.order() + 1)
                    .append(". 子句=").append(item.text())
                    .append("；允许候选=").append(item.resolution().candidateIds())
                    .append("；强度=").append(item.resolution().strength());
            if (item.condition() != null) {
                input.append("；条件=").append(item.condition());
            }
            input.append('\n');
        }
        return input.toString();
    }

    private static String summaryOf(CapabilityCard card) {
        return card.name() == null || card.name().isBlank() ? card.capabilityId() : card.name();
    }

    /** 会话号带上 traceId，便于把 OJ 侧的图日志与本次请求对上。 */
    private static String sessionId() {
        RequestContext ctx = RequestContextHolder.get();
        return "sp-" + (ctx == null ? UUID.randomUUID().toString() : ctx.traceId());
    }
}
