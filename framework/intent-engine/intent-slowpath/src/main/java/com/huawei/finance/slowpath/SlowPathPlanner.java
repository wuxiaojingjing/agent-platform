package com.huawei.finance.slowpath;

import com.huawei.finance.contracts.model.CapabilityCard;
import com.huawei.finance.oj.adapter.ModelGatewayClientFactory;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 慢路径主 Agent：把一句复合诉求拆成几步能力调用。
 *
 * <p>实现壳是 OJ {@link DeepAgent}（ADR-004）：任务环 / 内部规划 / 通用子 Agent / 异步子 Agent
 * 打开，以拿到完整 harness。三条边界不能松：
 * <ol>
 *   <li><b>只规划不执行</b>：工具是 {@link ProposalTool}。执行仍走中控（冲突 C1 缓解）。</li>
 *   <li><b>不产出面客文本</b>：{@link #plan} 只返回提案列表，不记录模型自述或原始目标。</li>
 *   <li><b>模型走本行通道</b>：{@code modelProvider} 固定为 {@code agent-platform}。</li>
 * </ol>
 *
 * <p>Workspace 是进程级草稿根（冲突 C2/C3）；续办权威在 Postgres，跨实例靠 FP-66 实例亲和。
 */
public class SlowPathPlanner {

    private static final Logger log = LoggerFactory.getLogger(SlowPathPlanner.class);
    private static final int MAX_OUTPUT_TOKENS = 1024;

    /**
     * 规划的系统提示词。
     *
     * <p>刻意写死在代码里而不是放进资产目录：它约束的是**Agent 的行为边界**（只规划、
     * 不承诺、不编能力），不是业务话术。放进资产会让它可以在不改代码的情况下被改掉，
     * 而这几条正是不该能被配置改掉的。业务话术在 {@code response-engine} 的模板里。
     */
    private static final String SYSTEM_PROMPT = """
            你是银行助手的任务规划器。用户的一句话可能包含多个诉求，你的工作是把它拆成
            若干步能力调用，并为每一步给出参数。

            必须遵守：
            1. 只能通过给定的能力工具（propose）列入计划，不得臆造能力。找不到合适的能力就直接说明。
            2. 你的调用只是「列入计划」，不会真的执行。不要据此声称某件事已经完成。
            3. 参数只能来自用户的原话或工具返回，不得猜测。缺少必要参数时如实说明缺什么。
            4. 不要写给用户看的回复，那由另一个环节负责。
            5. 按用户办理顺序多次 propose，一步一次；办理步骤必须经由能力工具留下提案。
            6. 不得执行系统命令或越权操作；通用探索类能力若存在，不得替代能力工具提案。
            7. 输入已经划定步骤边界；每个步骤只能从它允许的候选中选择，不能增删、合并或重排步骤。
            """;

    private final List<CapabilityCard> capabilities;
    private final String modelName;
    private final int maxIterations;
    private final String workspacePath;

    /**
     * @param capabilities  可用能力。由调用方按召回结果给，不是全量
     * @param modelName     规划模型
     * @param maxIterations 推理轮次上限
     * @param workspacePath 进程级 Workspace 根；空则用临时目录下固定子目录
     */
    public SlowPathPlanner(List<CapabilityCard> capabilities, String modelName, int maxIterations,
                           String workspacePath) {
        this.capabilities = List.copyOf(capabilities);
        this.modelName = modelName;
        this.maxIterations = maxIterations;
        this.workspacePath = workspacePath;
    }

    public SlowPathPlanner(List<CapabilityCard> capabilities, String modelName, int maxIterations) {
        this(capabilities, modelName, maxIterations, null);
    }

    /**
     * 规划。
     *
     * @param goal 用户原始诉求
     * @return 提案列表，按 Agent 给出的顺序。空列表表示它没找到可做的事
     */
    public List<TaskProposal> plan(String goal, AgentSessionApi session) {
        return plan(goal, session, com.huawei.finance.common.context.RequestContext.AGENT_ENTRY, "nosession");
    }

    /**
     * @param agentId   所属 Agent（作用域进 Workspace 路径）
     * @param sessionId 会话；与实例亲和同一粒度
     */
    public List<TaskProposal> plan(String goal, AgentSessionApi session,
                                   String agentId, String sessionId) {
        List<TaskProposal> proposals = new ArrayList<>();

        AgentCard card = new AgentCard();
        card.setName("agent-platform-slow-path-planner");
        card.setDescription("把复合诉求拆成能力调用计划");

        DeepAgentConfig config = deepAgentConfig(modelName, maxIterations);
        Workspace workspace = ProcessScopedWorkspace.get(workspacePath, agentId, sessionId);
        DeepAgent agent = new DeepAgent(card, config, workspace);

        for (CapabilityCard capability : capabilities) {
            agent.registerHarnessTool(new ProposalTool(capability, proposals));
        }

        Object output = agent.invoke(Map.of("query", goal), session);
        String outcome = hasError(output)
                ? "FAILED" : "SUCCEEDED";
        String reasonCode = "FAILED".equals(outcome) ? "PLANNER_ERROR" : "PLANNED";
        log.info("慢路径规划完成 goalLength={} capabilityCount={} proposals={} outcome={} reasonCode={}",
                length(goal), capabilities.size(), proposals.size(), outcome, reasonCode);

        return List.copyOf(proposals);
    }

    /**
     * 构造 DeepAgent 配置。包内可见便于单测断言四开关与禁 sysOperation。
     *
     * <p>见 ADR-004：较完整 harness；执行权威仍不移交。
     */
    static DeepAgentConfig deepAgentConfig(String modelName, int maxIterations) {
        return DeepAgentConfig.builder()
                .systemPrompt(SYSTEM_PROMPT)
                .maxIterations(maxIterations)
                .enableTaskLoop(true)
                .enableTaskPlanning(true)
                .addGeneralPurposeAgent(true)
                .enableAsyncSubagent(true)
                .model(ModelRequestConfig.builder()
                        .modelName(modelName)
                        .maxTokens(MAX_OUTPUT_TOKENS)
                        .build())
                .backend(ModelGatewayClientFactory.clientConfig())
                .build();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean hasError(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("error")) {
                return true;
            }
            return map.values().stream().anyMatch(SlowPathPlanner::hasError);
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (hasError(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
