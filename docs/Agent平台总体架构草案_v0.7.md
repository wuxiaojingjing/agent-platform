# Agent 平台总体架构草案 v0.7

> `ADR-010` 的代码与契约已落地：完整 Decision、入口/续轮路由、平台焦点、Static Plan 与
> 独立动态 Loop 已实现。Agent Loop 在开发、测试和生产配置中均默认开启；真实动作仍由
> `EffectiveLoopAccess`、权限、资产状态、预算和 `LoopActionPolicyGate` 逐层控制。
>
> 本文记录当前工程实现基线。面向 openJiuwen `agent-solution/finance` 的目标架构、复用边界和
> 重构计划见[《openJiuwen 金融面客智能体解决方案总体设计与重构计划 v0.1》](openJiuwen金融面客智能体解决方案总体设计与重构计划_v0.1.md)。

## 1. 目标

平台能力独立成包，被产品 Agent 和领域 Agent 以同一种方式集成。手机银行助手是拥有前后端、
一级路由、意图引擎和任务中控的超级 Agent，但不是平台本身。任何领域 Agent 也可以成为中控并
继续委托下级 Agent。

本版交付的是可替换的业务调用边界与本地契约模拟，不代表已连接生产银行核心系统。

## 2. 同构 Runtime

每个非 Scaffold Agent 都拥有以下本地边界：

```text
Agent Runtime
  -> Intent Engine
  -> Context Engine
  -> Task Orchestrator
  -> Guardrail / Idempotency
  -> Local Business Port | Capability Delegator
  -> Response Engine
```

上下文、任务、计划、会话记忆、工作记忆和缓存始终属于当前 Agent，并按
`tenantId + agentId` 隔离。共享中间件只共享资源，不共享所有权。

### 2.1 入口路由闭环

每个承担一级路由的 Agent 使用同一条入口链：

```text
EntryRouteCoordinator
  -> ContinuationGate
       -> DeterministicContinuationRules
       -> ContinuationUnderstandingModel（所有自然语言续轮）
       -> ContinuationPolicyGate
  -> IntentEvidenceBuilder
  -> DeterministicEntryRules
  -> TaskShapeModel（仅规则灰区；可与模型仲裁合并或按职责独立调用）
  -> LoopEntryPolicyGate
  -> RouteDispatcher
```

`Decision` 运行词表为：

```text
DIRECT_KNOWLEDGE | NAVIGATION | EXECUTE_CAPABILITY | START_WORKFLOW
STATIC_PLAN | DELEGATE_GOAL | START_LOOP | RESUME_TASK | RESUME_LOOP
CLARIFY | CANCEL | REJECT | HANDOFF
```

旧四出口不保留兼容层，所有 Agent、Schema、缓存、观测和测试必须在协调发布中一次性迁移。
`DeterministicEntryRules` 能确定的请求不调用模型；模型只判断规则灰区的候选与任务形态。
平台不再规定“每个入口请求最多调用一次模型”。简单灰区可以由一次合并调用完成；复杂候选仲裁、
补槽、任务形态复核或 Schema 修复可以按需分阶段调用。每次调用都必须有独立用途、预算、超时、
版本和观测记录，最终结论统一经过 `LoopEntryPolicyGate`，后续调用不能放宽风险与执行边界。
公共 RouteDecision 暴露 `TaskShape` 摘要，subGoals 与 `SelectionBasis` 只用于 Router 内部验证，
不进入 A2A，不成为执行状态权威。详细契约见
`入口意图路由实现与运营干预设计_v0.1.md`。

R0 默认 `confirmationPolicy=NONE`；R1 默认 `EXPLICIT`，允许能力声明 `REVIEW_ONLY`；
R2 强制 `EXPLICIT`。`REVIEW_ONLY` 和 `EXPLICIT` 都要求用户产生新的接受事件，完成前不得
生成幂等键或执行副作用。

### 2.2 跨任务挂起与恢复

任务业务状态与对话焦点分离。普通能力、Static Plan、Workflow 和 Agent Loop 统一使用平台级
`TaskContextManager` 登记平台任务引用和对话焦点：

```text
PlatformTaskStatus = OPEN | CLOSED
FocusState        = FOREGROUND | SUSPENDED | CLOSED
RuntimeState      = 由 Task / Static Plan / Workflow / Agent Loop 各自定义并持久化
```

`ContinuationGate` 只判断当前输入是续办、取消、恢复还是新目标，不保存业务状态。未完成任务中
出现新目标时先询问是否切换；接受后由 `TaskContextManager` 原子地将旧焦点改为 SUSPENDED 并
建立新前台焦点，不修改原 RuntimeState，再把新目标送回正常入口路由。恢复不会重放已完成动作，
也不会自动接受 Review/Confirm 或执行副作用。

平台记录与执行 Runtime 记录必须分开：平台保存 taskId、agent/session、routeTarget、焦点以及
`runtimeType + runtimeRef`；普通任务、Static Plan、Workflow 和 Agent Loop 分别保存自己的参数、
游标和执行状态。对于 Loop，平台只持有 loopId 引用，goal、LoopRun.status、facts、steps、
pendingAction 和 iteration 只由 `AgentLoopRepository` 记录。平台的粗粒度状态只是投影，不能用于
恢复 Loop。

待确认的新目标切换使用平台 `PendingSwitch`，绑定 foregroundTaskId、focusVersion、sourceTurnId
和 newGoalSpan。它只表达入口焦点变更建议，不保存或修改任何 Runtime 执行状态。

各 Runtime 通过只读 `RuntimeContinuationPort.describe(runtimeRef)` 提供本轮所需的待交互摘要和
allowedEvents。该摘要不在平台持久化；Loop 适配器必须从 `AgentLoopRepository` 现查现组装。

结构化按钮和 Runtime 声明的精确槽值走确定性快路；其余自然语言续轮由独立逻辑模型
`ContinuationUnderstandingModel` 判断。它可以和 TaskShape/仲裁
使用同一个底层模型实例，但必须拥有独立提示词、JSON Schema、版本、指标和缓存。
PendingSwitch 也进入同一续轮上下文；按钮与自然语言接受/拒绝共用 `ContinuationPolicyGate`。
自然语言 R2 Confirm 必须同时满足 0.95 置信门槛和模型契约
`confirmationStrength=EXPLICIT_ACTION`；PolicyGate 不解析中文短语。
同一会话最多保留 3 个挂起任务，达到上限时入口返回明确提示，不创建第 4 个挂起任务。
同步 Task/Workflow 处于 `RUNNING` 且没有独立完成回执通道时默认 `DENY_SWITCH`；只有 Runtime 明确
具备结果持久化和完成回执能力时才能声明后台继续。Static Plan 的 `IN_PROGRESS` 表示游标和事实已经
持久化、正在等待下一次同步推进，可以 `ALLOW_SWITCH`；接受切换只挂起平台焦点，不取消或推进 Plan。
PendingGoal 路由会绕过普通 Decision Cache；若新目标在 Runtime 创建前失败，平台把 PendingGoal
置为 FAILED、关闭临时焦点并恢复原任务，不留下悬空前台。

### 2.3 上下文改写与消费证据

入口上下文必须形成“读取 -> 编译 -> 改写 -> 消费 -> 决策”的闭环。`ContextLease` 不能只在意图识别
完成后参与槽位增强；上下文改写、事件分类和需要上下文的召回/规划必须接收同一状态版本的受控投影。
上下文改写输出独立 Query、`usedContextRefs` 和解析来源；词法规范化不得冒充上下文改写。

入口已经为 load/compile/rewrite 产生独立 Jaeger Span，并把同一状态版本的历史证据投影给
`ContextualQueryModel`。模型只输出受控语义槽位和引用；账户、余额等业务值由来源 Agent 的内部只读解析
能力按 Schema 白名单返回，执行前再次刷新。解析能力声明 `entryVisible=false`、`loopAccess=DENY`，不能被
入口或 Planner 自由选择。详细契约与用例见 `多轮上下文改写与跨Agent传递验收用例_v0.1.md`。
模型已经识别引用但权威上下文中不存在目标时，PolicyGate 输出 `UNRESOLVED_REFERENCE`；入口必须在召回前
短路为澄清，禁止把原句重新当作无上下文新目标，也禁止由领域执行器选择默认对象。

对话历史采用有序 `USER / ASSISTANT / TOOL / AGENT` 消息项，并用稳定 `callId` 配对调用和结果。
用户实际收到的助手文本、卡片展示槽位与按钮必须原样持久化，约束固定为
`userVisible => modelVisible`；下一轮不得从模板重建历史回复。Task/Plan/Loop 结果仍是独立事实权威，
对话文本没有执行授权。续轮模型、入口仲裁与 Loop Planner 接收同一份已裁剪 `IntentContext`；跨 Agent
仍只传最小结构化事实和引用，不传原始完整聊天记录。

## 3. A2A v2

协议版本固定为 `a2a/2`。信封包含任务层级、绝对 deadline、Trace、委托路径及主体上下文。

`PrincipalContext` 只允许：

- `principalRef`：不透明主体引用；
- `authLevel`：认证级别；
- `channel`：来源渠道；
- `sourceSessionRef`：不透明来源会话引用。

目标进程通过 `PrincipalResolver` 得到 `ResolvedPrincipal`。引用值不得进入日志、指标、Span、
控制台或业务回执。能力卡 `principalRequired` 默认 `true`，公开导航显式为 `false`。

目标会话键使用：

```text
SHA-256(sourceAgentId + sourceSessionRef + rootTaskId)
```

版本、主体、deadline、Trace、目标身份或环路校验失败均返回 FATAL reasonCode。

## 4. Runtime-backed 入站

生产 Extension Host 只装配 `RuntimeBackedAgentNode`：

```text
A2A Server
  -> PrincipalResolver
  -> AgentInvocationRuntime
  -> 目标上下文编译
  -> 目标任务建档
  -> 目标护栏与幂等
  -> 本地业务 Port / 下级 A2A 委托
```

TASK 跳过重复意图识别，但不跳过上下文、中控、护栏和状态。GOAL 运行目标 Agent 的完整意图
Runtime。`DomainAgentNode` 仅保留在测试/样例中，不参与生产 Host 装配。

目标任务的意图解析模式为 `DIRECT | REASONING_LOOP`：`DIRECT` 包含确定性入口及多路召回、融合、
仲裁等可直接产出意图的组件；`REASONING_LOOP` 才是使用更大模型和识别工具循环取证的真正慢路径。
历史字段若仍为 `FAST_PATH | SLOW_PATH`，仅作为迁移兼容值，不再用来定义执行方式。调用来源为
`invocationOrigin=A2A`，`sourceInvocationId=delegationId`。数据库以
`agentId + invocationOrigin + sourceInvocationId` 建唯一约束，重放返回相同 taskId 和原 `TaskResult`。

本地叶子只在 A2A 已到达目标 Agent 后优先执行，防止自委托；不属于本 Agent 的能力继续通过
Gateway 委托。网络错误不得回落本地执行。

## 5. Static Plan 与 Agent Loop

静态计划和动态 Loop 必须分开：

- `STATIC_PLAN` 使用现有 `IntentPlan`、条件表达和持久化游标；
- `START_LOOP` 使用独立 LoopRun/LoopStep 状态，执行一步、观察真实结果后再规划；
- 条件只决定一个已知动作是否执行时属于 Static Plan；
- 真实结果决定接下来选择哪个能力，或目标是开放式诊断时才属于 Agent Loop。

因此“工资没到账就检查工资卡状态”是 `STATIC_PLAN + RESULT_RULE`；“工资没到账，帮我排查原因”
才是 `START_LOOP + AFTER_OBSERVATION`。多意图、跨域和长句本身都不是进入 Loop 的充分条件。

`StaticPlanCoordinator` 收敛现有名为 `intent-slowpath` 的静态计划执行器，继续使用 IntentPlan 与计划
步骤表；该模块名是历史遗留，不表示“意图 Slow Path”。动态 Loop
由 `AgentLoopStarter/AgentLoopCoordinator` 驱动，并使用独立 `agent_loop_run/agent_loop_step`。
Static Plan 的已确认参数保存在 `agent_intent_plan.parameters`，与步骤、游标同属 Static Plan
Runtime 权威状态；平台任务表只保存 `runtimeRef=planId`。
Static Plan 保留现有执行配置：

配置：

```yaml
huawei.finance.agent.slowpath:
  execution-mode: DISABLED | CONFIRM_EACH | AUTO_READ_ONLY
  max-auto-steps: 5
```

默认 `CONFIRM_EACH`。`AUTO_READ_ONLY` 同时满足下列条件才执行：

- R0；
- 无副作用；
- 必填参数完整；
- 主体满足能力声明；
- 条件求值为 `PROCEED`；
- deadline 未到。

每一步单独建任务、跑护栏、落结果，再用条件更新推进计划游标。最多推进 5 步。遇到缺参、
条件不确定、需要 Review/Confirm、副作用、NEED_USER、PARTIAL 或失败立即停止，不提前生成下一步幂等键。
已完成事实按 capabilityId 命名空间聚合，失败不删除先前事实；进程重启后从数据库游标继续。

动态 Loop 的运行链为 `Planner -> Validator -> PolicyGate -> claimAction -> Execute -> Observe ->
completeStepAndAdvance -> Replan`，详细输入输出和持久化设计见
`AgentLoop框架与模块输入输出设计_v0.1.md`。其中 `ModelAgentLoopPlanner` 通过既有 Slow Path 边界内的
`DeepAgentSingleActionPlanner` 运行仅含 Proposal Tool 的单动作 DeepAgent，模型不能直接调用业务接口。
`huawei.finance.agent.loop.enabled=true` 是开发、测试和生产的统一默认值。依赖不可用、预算耗尽或
结果不确定时进入受控 `HANDOFF`/失败回复，不把开放目标伪装成静态计划，也不依靠关闭生产总开关降级。

## 6. 十一个领域 Extension

| Agent | 业务 Port | 本地适配 |
| --- | --- | --- |
| account | `AccountPort` | 余额、流水 HTTP |
| transfer | `TransferPort` | 幂等转账 HTTP |
| creditcard | `CreditcardPort` | 账单、还款、换卡 HTTP |
| wealth-aggregate | `WealthPort` | 持仓 HTTP |
| fund-service | `FundProductPort` | 基金产品 HTTP |
| insurance-service | `InsuranceProductPort` | 保险产品 HTTP |
| finance-assistant | `NavigationCatalogPort` | 版本化菜单资产 |
| deposit-service | `DepositProductPort` | 存款产品 HTTP；存折、换卡知识由知识资产承接 |
| loan-service | `LoanProductPort` | 贷款产品 HTTP；未开放申请走菜单/知识/人工降级 |
| payroll-service | `PayrollPort` | 工资代发状态 HTTP；固定条件走 Static Plan，开放排查走 Loop |
| wealth-product | `WealthProductPort` | 理财产品 HTTP；购买/赎回不在执行边界内 |

`TechDomainAgent` 只负责契约校验、Port 调用和 `TaskResult` 映射，不保存业务常量。HTTP 适配器
拥有独立 base URL、连接/读取 timeout；空响应与超时按后端不可用处理。读超时/5xx 为
RETRYABLE；副作用结果未知为 PARTIAL；缺业务参数为
NEED_USER；校验失败为 FATAL；重复幂等请求返回首次结果。

`samples/agents/banking-systems-simulator` 是本地确定性后端，不注册 Nacos Agent 身份，不计入
27 个 Agent。关闭模拟服务后禁止回退硬编码数据。

## 7. 观测

同一 Trace 应覆盖入口意图、中控、A2A Client、Gateway、目标 A2A Server、目标 Runtime、目标
上下文/任务和下级委托。目标侧固定增加 `agent.a2a.target.runtime`、`agent.context.compile`、
`agent.task.orchestrate` 三个 Span。低基数标签包含 sourceAgent、targetAgent、capability、mode、outcome、
reasonCode 和 implementationMode；taskId/delegationId 只作 Span 属性。

目标回执分别展示 `targetTaskId`、`intentPath=FAST_PATH|SLOW_PATH`、
`invocationOrigin=A2A` 和 `principalVerified`，不得展示主体引用。快/慢只描述意图识别，
A2A 只描述识别后动作进入当前 Agent 的调用来源，两者不得合并。控制台
继续按 session -> turn 折叠，展示主子协同、模块脱敏输入输出、全局上下文、会话记忆、工作记忆
和决策缓存。观测数据不是审计流水。

Slow Path 日志只记录 goalLength、候选/步骤数、outcome 和 reasonCode，不记录完整目标或模型自述。

入口侧还必须产生 `agent.context.load`、`agent.context.compile`、`agent.context.rewrite`、
`agent.intent.recall` 和 `agent.intent.arbitrate` Span。控制台模块步骤与 OTel Span 是两类消费者：前者用于
脱敏 I/O 复核，后者用于分布式时序和故障定位，记录其中一个不能替代另一个。

上下文自然语言理解由独立 `ContextualQueryModel` 负责，采用“模型优先、知识补强、
PolicyGate 守门”。评测证明模型稳定漏识后才增补 Prompt/术语/能力知识，不在 Java 中
穷举“第几张卡”、“一半”或纠错句式。模型产出 `accountOrdinal` / `amountBasis` 等语义槽位；
工具返回的余额和卡片允许进入会话级记忆并被模型引用，但 Context 阶段只保存事实引用、账户映射和
计算依据，不生成具体 `amount`。版本校验、确认后余额重查与金额计算仍为确定性执行运行时责任。

该原则同样适用于 `ContinuationUnderstandingModel`、`TaskShapeModel` 和 `AgentLoopPlanner`：先评测
模型基线，再针对可复现缺口补充版本化知识。只有健康基线模型在至少两个自然改写上稳定失败，
且根因确认为 `DOMAIN_KNOWLEDGE_GAP`，才允许将知识标记为 `EVALUATED_GAP` 并注入 Prompt。补强样例还必须
携带基线失败证据、正例回归和负例回归；线上低置信、越权或非法输出直接澄清/降级，不执行“失败后补知识再猜一次”。
知识资产负责提高理解覆盖率，PolicyGate 继续负责引用、状态、权限、风险、确认和执行边界。

## 8. 部署与验收

本地完整环境为 27 个 Agent、独立 Gateway、Nacos、PostgreSQL、Redis、OpenSearch、模拟服务和
Jaeger/Prometheus/Grafana/Loki/Alloy。模拟服务不计 Agent 数。

验收包括：

- A2A v2 主体、版本、deadline、Trace、环路、隔离和重放；
- 新 Decision 全量迁移，不接受旧四出口、旧缓存或旧模型输出；
- 确定性请求零模型调用，规则灰区只有一次合并的仲裁/任务形态调用；
- STATIC_PLAN 与 START_LOOP 的 RESULT_RULE/AFTER_OBSERVATION 分界；
- TaskShape 模型的 subGoals、dependsOn 和 SelectionBasis 经过候选范围与组合语义校验；
- R1 REVIEW_ONLY/EXPLICIT 与 R2 EXPLICIT 在执行前不产生幂等键；
- 新目标切换先询问，旧任务状态可挂起并恢复，恢复不重放已完成动作；
- 自然语言续轮及切换不得绕过 PolicyGate，R2 模糊表达不得形成确认事件；
- GOAL/TASK 都产生目标任务并经过目标护栏；
- 多层委托 Trace 连续且每层任务独立；
- 单 Agent 多轮改写能够证明使用了哪些历史/事实引用，读取到历史但 `usedContextRefs` 为空时不得误判通过；
- 入口 -> 子 Agent A -> 子 Agent B 的事实经主 Agent 投影，包含来源、时效、作用域和状态版本；
- 异构子 Agent 通过 JSON Schema/TCK 接入，不依赖 Java 类型；不支持上下文增量契约时不得执行 R1/R2；
- Jaeger 同时覆盖入口上下文装载/编译/改写和每层 A2A 上下文/任务 Span；
- `IntentReasoningLoop` 只调用候选、知识、上下文等识别工具，不能创建幂等键、改任务状态或调用业务；
- Static Plan 与 `ExecutionAgentLoop` 的执行推进及全部停止条件；
- 11 域 Port 成功、缺参、超时、5xx、幂等和结果未知；
- 15 Scaffold 返回 `DOMAIN_NOT_OPEN` 且事实为空；
- JDK 21 Reactor、Live Test、前端、Compose 与 Smoke；
- 删除产品模块后 Framework、Infrastructure、Host、Gateway 仍可构建。

实现对应的 Flyway 迁移为 `V10__platform_task_context.sql`、`V11__focus_owns_concurrency.sql`、
`V12__agent_loop.sql`、`V13__task_state_version.sql`、`V14__intent_plan_state_version.sql`、
`V15__loop_claim_recovery.sql`、`V16__intent_plan_parameters.sql` 和
`V17__loop_confirmed_slots.sql`；V17 为 Loop 权威记录增加 confirmedSlots 与 pendingSlots；
模型契约位于 `framework/contracts/agent-api/src/main/resources/schema`，
入口、续轮和 Loop Prompt 位于手机银行助手资产目录。本地全 Reactor、前端测试与构建、分发
构建已经通过；当前代码在真实 PostgreSQL 下通过 Loop JDBC `4/4` 和平台焦点 JDBC `9/9`，
最新镜像通过核心 E2E `16/16`（跳过 `0`）、Compose P0 `39/39` 与 P1 `12/12`；
真实模型工资排查连续 `3/3` 按 `CALL_CAPABILITY -> Observation -> FINISH` 完成。
`context-acceptance.sh` 已通过全部离线证据、7 条真实模型/上下文链路及对应 Jaeger 父链和隐私检查；所有
上下文用例已标记为 `locked`。后端测试使用
独立 OpenSearch 索引，避免测试模型覆盖生产向量。生产发布动作仍须协调完成，不能新旧版本混跑。

代码命名与设计名的对应关系：`TaskShapeModel` 是升级后的 `ModelArbitrator`；平台任务登记与焦点
由 `PlatformTaskContextManager + TaskContextStore` 实现；Loop 的受控上下文、状态选择与续轮恢复
分别由 `LoopContextBuilder`、`LoopStateMachine`、`LoopContinuationViewProvider` 和
`LoopResumeAdapter` 实现，最终状态仍由带 CAS 的 `AgentLoopRepository` 提交。Loop 的
confirmedSlots/pendingSlots 只存在 Loop 权威记录，平台任务和焦点表不复制这些字段。
`RouteDispatcher` 做 Decision 到处理器的穷尽映射，并统一委托 `PlatformRuntimeBridge` 完成
reservation/binding/terminal close；它不读取用户文本，也不保存 Runtime 业务状态。

## 8.1 2026-08-02 多场景与回复策略增量

- `CapabilityCard` 增加 `implementationStatus / positiveBoundary / negativeBoundary /
  fallbackCapabilityIds`。`SCAFFOLD` 只能配合 `status=DISABLED`，不可获得执行权。
- 当前目录共 27 个 Agent：12 个 `IMPLEMENTED`（含入口导航 Agent）和 15 个显式
  `SCAFFOLD/DISABLED`。未建设债券 Agent 的“购买储蓄国债”示例降级为
  `cap.nav.bond_service_储蓄国债`，只打开菜单，不提交债券交易。
- 回复实现统一为 `ResponsePlanner -> ResponseRealizer -> AnswerAudit -> ResponseEnvelope`，
  `RenderMode=TEMPLATE|MODEL_SELECT|POLISH|GENERATE`。模型只能改变面客文字；按钮、ref/version、
  状态、金额、收款人、卡片、确认强度、风险提示和幂等状态均由确定性计划保留。
- 回复策略按 tenant、Agent、scene、ResponsePhase 选择，默认 `TEMPLATE`；本地/测试通过 AssetStore
  热更新，生产 `write-enabled=false`，只接受 Git/MR 发布。
- 小 i 首批内化 6 条审批标准问，21 条样本全部进入迁移台账并记录 lineage；损坏、占位或缺答案记录
  保持 `BLOCKED_SOURCE_REVIEW/BLOCKED_ANSWER_APPROVAL`，不由模型补造。
- `XiaoiExternalEvidence` 保留 callStatus、matchStatus、resultType、knowledgeId、rawScore、dimension、
  actionRefs、legacySessionRef 和 sourceVersion。rawScore 是外部证据元数据，不与 BM25、向量或规则分直接相加。
- PendingGoal 接受切换后必须跳过 ContinuationGate，经 `routeResolvedGoal` 路由一次；否则同一新目标会
  再次触发切换复核。平台只切换焦点，原 Task/Plan/Workflow/Loop 参数仍由原 Runtime 保存。
- Loop 的 CLARIFY/REVIEW/CONFIRM/FINAL 不再由桥接层手写裸回复计划；统一解析 ResponsePolicy，
  最终摘要由领域模板或审批知识生成，禁止把 `completedFacts.toString()` 暴露给用户。
- `loop-planner-v5` 要求先判断已提交 facts 是否足以回答目标；足够时立即 `FINISH`，不得为了
  遍历候选或重复已执行 capabilityId 而继续探测。重复动作门禁仍作为通用安全兜底。

## 9. Git 与秘密

目录迁移一次完成，不保留旧包双写。Git 预检只输出文件分组和秘密阻断状态，不暂存或提交。
当前已知明文秘密整改按决策暂缓，因此正式提交和发布仍是阻断状态；本版不删除、不打印、不轮换
现有本地密钥。
