# 多轮上下文改写与跨 Agent 传递验收用例 v0.1

## 1. 问题来源

2026-08-01 复核 Trace `9f343afa994f4962ed6f2fd90c3e01c9` 时发现：入口实际执行了
`conversation-memory.recent`、`context-engine.compile-lease` 和 `intent-rewrite.rewrite`，但 Jaeger
只展示 HTTP、意图、中控和回复四段。控制台模块记录能够证明历史被读取，却不能证明历史被后续模块消费。

同一会话的后续轮进一步证明当前 `intent-rewrite` 只接收本轮 `rawQuery`。`ContextLease` 只在意图识别
完成后参与槽位增强，且当前租约只投影历史工具结论，不包含可供改写使用的对话证据。因此下列三件事必须
分别验收，不能互相替代：

1. 历史读取成功；
2. 相关历史进入本轮受控上下文；
3. 改写、事件分类、召回或规划明确消费了哪些上下文项。

## 2. 目标链路

```text
ConversationTurn / RuntimeState / ToolResult
  -> ContextLeaseCompiler
  -> IntentContext（本轮受控投影）
  -> ContextualQueryRewriter
  -> LexicalQueryNormalizer
  -> EventClassifier / Recall / Arbitration / Planner
  -> UnifiedTask | SubtaskContextEnvelope
```

`ContextualQueryRewriter` 与现有词法改写必须分开。前者负责省略、指代、纠正和任务焦点解析，后者只负责
中文数字、词项、搜索文本和本轮槽位规范化。只有词法归一结果，不得在观测或验收中标记为“上下文改写”。

### 2.1 四类上下文必须分层

本地上下文不再把一轮压成单个 `USER_TURN`，而是保存与模型工具调用协议一致的有序消息项：

```text
USER(TEXT)
ASSISTANT(TOOL_CALL, callId)
TOOL|AGENT(TOOL_RESULT|AGENT_RESULT, same callId)
ASSISTANT(TEXT, exact rendered text + visible card/action projection)
```

`messageId` 标识消息，`callId` 只标识一次调用并强制配对。工具或 Agent 的结构化结果进入模型可见历史，
但仍由独立 `facts`/Runtime 记录持有执行权威；对话里的文本和卡片内容不能自动变成可执行参数。
任何 `userVisible=true` 的消息必须同时满足 `modelVisible=true`，构造期即拒绝例外。按钮轮即使没有 query，
也按结构化 USER 消息保存；助手回复保存实际返回渠道的精确文本、`responsePhase`、可见卡片槽位和
`ResponseAction`，不得在下一轮用新版模板重新渲染。

四类数据边界如下：

| 数据 | 权威归属 | 本地模型如何使用 | 是否跨 Agent 原样传递 |
| --- | --- | --- | --- |
| 对话消息 | `ConversationTurn.messages` | 理解省略、指代、用户已看到内容 | 否 |
| 执行事实 | Task/Plan/Loop 结果与 Observation | 受引用、来源、时效约束的证据 | 仅最小 `ContextEvidence` |
| Runtime 状态 | Task/Workflow/Static Plan/Loop | 当前目标、已确认值、待办、最近结果 | 否，只传任务需要的摘要 |
| 平台焦点 | PlatformTask/Focus/PendingSwitch/Goal | 决定当前读取哪个 Runtime | 否 |

`TurnContextAssembler` 每轮只读取一次焦点和 Runtime 摘要，并把同一个 `IntentContext` 交给
`ContextualQueryModel`、续轮理解、入口仲裁和 Loop Planner。Runtime/A2A 附加证据与对话历史共用
同一 token 预算；超预算按证据项裁剪并留下 `TrimmedItem`，不能绕过租约预算。

Task 快照携带已持久化 `TaskResult`，Static Plan 携带最近 5 个步骤事实，Loop 携带已提交 facts 和
最近 5 个 Observation。这些全部是只读 `runtimeFacts`，不会并入 `confirmedFacts`。

自然语言上下文理解采用“模型优先、知识补强、规则守门”：

1. `ContextualQueryModel` 负责指代、省略、纠错和任务关系，输出标准化查询与语义槽位；
2. `ContextRewritePolicyGate` 校验原文跨度、可用引用、状态版本、置信度和槽位值域；
   已识别但不存在或越界的引用必须输出 `rewriteOutcome=UNRESOLVED_REFERENCE`，由入口在召回、仲裁和执行前
   统一转为 `CLARIFY + UNRESOLVED_REFERENCE`，不得退化为普通新意图或回退到列表第一项；
3. 模型低置信、输出越权或不可用时不用 Java `if/regex` 猜测，保持原句并进入澄清/安全降级；
4. 只有版本化评测证明某类表达持续失败后，才增补 Prompt 示例、领域术语、能力卡、标准问等知识资产。

知识补强是离线能力建设，不是线上失败后的第二次猜测。基线模型必须先在同义改写集上单独评测；
同一语义缺口在多个自然表达上可稳定复现时，才允许增补知识。每个补强项必须关联失败评测、资产版本及正反回归用例；
无失败证据时不为“可能有用”堆积示例。知识可以改善模型理解，但不得改写引用、版本、风险、确认和执行 PolicyGate。

具体准入顺序固定为：先以空补强样例运行基线模型评测；再将可稳定复现的失败归为模型能力缺口或领域知识缺口；
只有后者才新增知识样例。样例必须声明 `activation=EVALUATED_GAP`、`baselineModelHealthy=true`、
`baselineModelVersion`、`baselinePromptVersion`、`failureCategory=MODEL_SEMANTIC_FAILURE`、
`gapType=DOMAIN_KNOWLEDGE_GAP`、`failedEvaluationIds`、至少两个不同的 `failedParaphraseCaseIds`、
`positiveRegressionCaseIds` 和 `negativeRegressionCaseIds`。证据不完整时资产可以保存为 `DRAFT`，但运行时不会注入模型。
补强后必须重新运行全量正反评测，不能只验证原失败用例。模型在线输出不合格时仍直接由 PolicyGate 澄清或降级，
不得在同一请求中临时检索补强知识后再次调用模型。

| 失败类型 | 知识准入 | 处理方式 |
| --- | --- | --- |
| 健康基线模型在多个自然改写上稳定失败，且根因是领域知识 | `ADMIT` | 补版本化知识并跑正反回归 |
| 模型/网络不可用、超时 | `REJECT` | 修复基础设施或模型路由 |
| JSON/Schema/token 截断 | `REJECT` | 修复契约、Prompt 或 token 配置 |
| PolicyGate 拒绝 | `REJECT` | 检查越权、引用、版本、阈值和风险证据 |
| Runtime/域解析/A2A 未承接 | `REJECT` | 修复执行链路和扩展装配 |
| 单次失败或通用模型能力不足 | `REJECT` | 扩大评测或升级模型，不用知识掩盖 |

模型只能把“第二张卡”和“一半”输出为 `accountOrdinal=2` 与
`amountBasis=REQUERY_THEN_HALF`。账户域再根据权威有序账户事实做确定性引用映射；
模型不得输出 `__card`/`__half` 内部标记。余额与卡片事实可以进入会话级记忆并作为引用依据，
但上下文改写和会话记忆填充只保存事实引用、账户序号与计算依据，不生成 `amount` 或计算后数值。

上下文改写至少输出：

```json
{
  "originalQuery": "用第二张卡转一半给张三",
  "standaloneQuery": "使用账户引用 account-ref-2 的最新可用余额一半转给张三",
  "eventType": "NEW_PARALLEL_TASK",
  "resolutions": [
    {"mention": "第二张卡", "contextRef": "fact:accounts", "sourceTurnRef": "turn:1"},
    {"mention": "一半", "resolution": "REQUERY_THEN_HALF", "sourceTurnRef": "turn:1"}
  ],
  "usedContextRefs": ["fact:accounts", "tool:cap.account.balance.query@turn:1"],
  "unusedContextRefs": [],
  "slotUpdates": {"accountOrdinal": 2, "amountBasis": "REQUERY_THEN_HALF"}
}
```

金额快照可以作为会话事实帮助理解“一半”的计算对象，并可通过 `contextRef` 进入本轮上下文；
Context 阶段只形成 `amountBasis=REQUERY_THEN_HALF`，不形成具体转账金额。确认页向用户展示相同的
相对金额语义；确认后由执行参数解析器重新查询权威余额并计算本次执行金额。

模型输出到业务参数之间增加远程权威解析链路：

1. `ContextualQueryModel` 只输出 `accountOrdinal`、`amountBasis` 和已经存在的 `contextRef`，不能选择目标 Agent、解析能力或业务账户值；
2. `A2ARemoteDomainReferenceResolver` 根据通过 PolicyGate 的 `contextRef.sourceAgentId` 定位权威来源 Agent；
3. 来源 Agent 仅暴露内部只读能力 `cap.account.reference.resolve`。该卡声明 `entryVisible=false`、`loopAccess=DENY`，不进入入口召回或 Loop 候选；
4. A2A 请求只发送解析能力 `inputSchema` 白名单字段，回执只接受 `outputSchema.resolvedSlots` 白名单字段；
5. `A2AExecutionParameterResolver` 在 Review/Confirm 接受后、真正执行前再次调用同一权威能力，刷新账户引用和金额；
6. `ContextResolutionPolicyGate` 在缺必填值时返回 `CLARIFY`，无法安全恢复时返回 `REJECT`，不得生成参数不完整的确认页。

因此，“第二张卡”和“一半”是否被理解属于模型能力；“第二张卡究竟是哪一个账户、最新余额是多少”属于
权威 Runtime 解析。后者失败是 Runtime/A2A 问题，不能通过增加 Prompt 示例或领域话术绕过。

## 3. 跨 Agent 上下文规则

跨 Agent 不传完整聊天历史。主 Agent 从本地状态与记忆编译 `SubtaskContextEnvelope`，只投递目标子任务
需要的事实、约束和引用；子 Agent 通过 `ContextDelta` 返回增量，不能直接写主 Agent 状态库。

主 Agent 向子 Agent 投递时至少携带：

- `rootTaskId / parentTaskId / subtaskId / delegationId`；
- `contextLeaseId / baseStateVersion / expiresAt`；
- `goal / confirmedInputs / evidenceRefs`；
- `allowedCapabilities / readScopes / writeScope`；
- 结构化事实的 `sourceAgentId / sourceTaskId / observedAt / validUntil / sensitivity`。

子 Agent 回传至少包含 `baseStateVersion`、结构化结论、事实来源、有效期、待澄清项和记忆写入建议。
主 Agent 以 CAS 合并；账户、金额、权限、确认和交易结果冲突不得自动覆盖。

异构 Agent 可以使用不同语言、框架、模型和记忆实现，但必须通过同一 JSON Schema 和 TCK。目标 Agent
不支持 ContextDelta 或作用域契约时，只能降级为无状态只读 TASK；不得接收完整历史，也不得执行 R1/R2。
开发者创建、注册和验证自有子 Agent 的步骤见
[开发者自建子 Agent 接入指南](开发者自建子Agent接入指南_v0.1.md)。

## 4. 核心验收用例

| ID | 轮次/协作 | 关键断言 |
| --- | --- | --- |
| CTX-S01 | “查余额” -> “第二张呢” | 第二轮独立改写包含账户列表引用；不是只把“第二张呢”送去召回 |
| CTX-S02 | “给张三转 1000” -> “不是张三，是李四” | 事件为 CORRECTION；只回滚未执行动作；旧收款人不得残留 |
| CTX-S03 | 转账待确认 -> “先查下基金” -> “继续刚才的” | 原任务挂起后可恢复；恢复不重放动作、不自动确认 |
| CTX-S04 | “查工资卡余额” -> “那张卡最近三笔呢” | 指代解析到同一账户引用；流水能力收到受控引用 |
| CTX-A01 | 入口 -> 账户 Agent -> 转账 Agent | 账户有序列表经主 Agent 投影给转账 Agent；两个子 Agent 不共享数据库 |
| CTX-A02 | 账户余额快照 -> “第二张卡转一半” | 会话记忆保存账户事实和计算依据但不生成金额；确认后按权威余额求值 |
| CTX-A03 | 子 Agent 返回 NEED_USER -> 用户补参 | 补参回到原目标会话/任务；使用新 delegationId，不重复已完成步骤 |
| CTX-A04 | 主 Agent 并发调用两个产品 Agent | facts 按命名空间合并；列表顺序或关键字段冲突触发重读/澄清 |
| CTX-A05 | 入口 -> 理财聚合 Agent -> 基金 Agent | 三层 Trace 连续；每层任务独立；只逐层传最小上下文 |
| CTX-A06 | Java 主 Agent -> Python/低码子 Agent | Schema、作用域、版本和回执 TCK 通过；不依赖 Java 内部类型 |
| CTX-A07 | 同 sourceSession、不同 rootTaskId | 目标侧派生会话隔离，上一根任务的临时事实不可见 |
| CTX-A08 | 子 Agent 使用旧 baseStateVersion 回写金额 | CAS 拒绝；不得以最后写入覆盖主 Agent 新确认值 |
| CTX-A09 | 子 Agent 只返回自然语言“已成功” | 强制信封校验失败；不得写事实、不得生成成功话术 |
| CTX-A10 | A2A 中包含主体和账户信息 | 只传不透明引用；日志、Span、指标和 diagnostics 均无明文 |
| CTX-S05 | 工具调用后追问 | TOOL_CALL 与 TOOL_RESULT 使用相同 callId；下一轮保留调用顺序和精确结果 |
| CTX-S06 | Agent 委托后追问 | AGENT_RESULT 带来源进入主 Agent 历史；不复制子 Agent 全量会话 |
| CTX-S07 | Review 卡片 -> 按钮“继续” | 空 query 的结构化动作和上轮实际卡片/按钮均对续轮模型可见 |
| CTX-S08 | 同 sessionId、不同 tenantId | PostgreSQL 与 Redis 均不可跨租户读到消息或版本 |
| CTX-S09 | 查余额 -> 第二张卡给张三转账（缺金额）-> 基金产品A -> 恢复 -> 补金额 -> 确认 | 按钮和自然语言恢复均回到同一 transfer taskId；accountOrdinal/fromAccount/payee 不丢失，基金任务不继承转账私有槽位 |

版本化机器可读用例位于
`agents/mobile-banking-assistant/eval/context-continuation-cases.yaml`。`locked` 表示行为已实现、已有可执行证据并纳入发布门禁；
`known-gap` 只能表示已冻结但尚未实现的目标，不得计入通过率或删除来获得绿色报告。当前清单中的用例已全部 `locked`。

## 5. 观测验收

需要模型路由的本地新请求应产生以下父子 Span：

```text
http post /api/v1/chat
  -> agent.context.load
  -> agent.context.compile
  -> agent.context.rewrite
  -> agent.intent.recall
  -> agent.intent.arbitrate
  -> agent.task.orchestrate
  -> agent.response.render
```

显式 Agent/Workflow 等由确定性入口规则命中的请求不伪造
`agent.intent.recall/agent.intent.arbitrate`；此路径使用 `deterministic-a2a` 验收 profile，仍强制校验
context load/compile/rewrite、task orchestrate、完整 A2A 父链和隐私字段。

跨 Agent 继续包含 `agent.a2a.client`、`agent.a2a.gateway.route`、`agent.a2a.server.execute`、
`agent.a2a.target.runtime` 和目标侧 context/task Span。Span 只记录低基数属性和引用 ID，不记录用户原话、
主体引用值、账户号、金额明文或完整上下文。

每次改写必须能从控制台或测试夹具读取以下脱敏证据：输入上下文项数量、候选引用、实际使用引用、
独立改写摘要、裁剪数量、状态版本和降级原因。仅出现 `returnedTurns > 0` 不算上下文消费成功。

## 6. 自动化分层

1. `ContextLeaseCompilerTest`：预算、历史证据选择、来源与裁剪。
2. `ContextualQueryRewriterTest`：省略、指代、纠正、无关历史和冲突事实。
3. `Scene5WorkingMemoryTest`：入口、账户子 Agent、转账子 Agent 的完整三轮链路。
   同类用例还覆盖 PendingGoal 跳过 ContinuationGate，以及按钮/自然语言两条跨任务恢复通道。
4. A2A Contract TCK：ContextEnvelope/Delta、异构 JSON、权限作用域、版本冲突和敏感字段。
5. Compose E2E：真实 PostgreSQL/Redis/Gateway/两个以上 Agent Host，并查询 Jaeger 验证 Span 集合。

测试必须断言“使用了哪个上下文引用”和“哪个 Agent 产生了该事实”，不能只断言最终回复碰巧正确。

## 7. 当前验收状态

截至 2026-08-01，`scripts/context-acceptance.sh` 已完整通过：离线证据无跳过，自然语言续轮、仲裁和上下文三类模型
消费者均只注入 `EVALUATED_GAP` 知识，三份正式 Prompt 的 `examples` 仍为空。真实环境完成了有效序数引用、
无上下文澄清、越界澄清、收款人纠正、开放槽恢复、半额转账 Review 和最终执行 7 条链路；对应 Jaeger Trace 全部
父链收敛且隐私检查为 clean。
