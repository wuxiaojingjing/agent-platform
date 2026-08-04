package com.huawei.finance.gateway;

import com.huawei.finance.stability.Spi;
import java.util.List;

/**
 * 模型网关。
 *
 * <p>实施架构 §2.5.6 的立场是「模型能力一律经网关，应用进程内不直连厂商 SDK、不散落密钥」。
 * 行外用硅基流动的 OpenAI 兼容接口实现，行内换成统一推理入口时只替换实现类，
 * 上层不改一行——前提是上层只依赖这个接口，不去 import 任何厂商类型。
 *
 * <p>所有方法都不抛业务异常，不可用通过 {@link GatewayResult#available()} 表达。
 */
@Spi
public interface ModelGatewayClient {

    /**
     * 批量向量化。
     *
     * <p>调用方负责拼指令：Qwen3-Embedding 是 instruction-aware 的，query 侧拼检索指令、
     * 文档侧不拼（实施架构 §2.5.6 落地约束 1）。网关不感知指令模板，否则指令版本就有两个来源。
     */
    GatewayResult<List<float[]>> embed(List<String> inputs);

    /** 结构化 Chat，返回模型输出的原始文本。解析与 Schema 校验由调用方负责。 */
    GatewayResult<String> chat(ChatRequest request);

    /**
     * 带工具的多轮 Chat，供慢路径 Agent 的推理循环使用。
     *
     * <p>默认返回不可用。这是给实现方留的**可选能力**：快路径一次都不用它，
     * 行内若只接了单轮推理接口，慢路径 Agent 起不来，但快路径照常工作。
     * 默认实现返回不可用而不是抛，是为了让「没接」与「接了但这次不行」走同一条处理分支。
     *
     * <p>用途由 {@code BudgetAwareModelGateway} 记为 {@code agent-tools}，与面客链路的
     * embedding/arbitration/rerank 分开看（ADR-003）。轮次上限由 Agent 的 maxIterations 管。
     */
    default GatewayResult<ToolChatReply> chatWithTools(ToolChatRequest request) {
        return GatewayResult.unavailable("tool-calling-unsupported", 0);
    }

    /**
     * 重排。默认关闭；开启后多一次网关调用，是否启用看延迟与效果，不再被次数硬门否决
     * （ADR-003；实施架构 §4.4 规则 1 以该 ADR 偏离）。
     */
    GatewayResult<List<RerankHit>> rerank(String query, List<String> documents, int topN);

    /** 网关是否具备调用条件（密钥齐全、熔断器未打开）。 */
    boolean available();
}
