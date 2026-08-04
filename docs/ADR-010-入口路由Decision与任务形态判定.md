# ADR-010：入口路由采用完整 Decision，并区分 Static Plan 与 Agent Loop

| 项 | 内容 |
| --- | --- |
| 状态 | 已接受并实现；代码、真实中间件、核心 E2E、P0 `39/39` 与 P1 `12/12` 已验证 |
| 日期 | 2026-08-02 |
| 影响范围 | 入口路由、意图仲裁、公共契约、任务状态、缓存、观测、A2A 交接 |
| 关联 | `Agent平台总体架构草案_v0.7.md`、`入口意图路由实现与运营干预设计_v0.1.md`、`AgentLoop框架与模块输入输出设计_v0.1.md` |

## 1. 背景

旧四出口：

```text
FAST_EXECUTE | CLARIFY | SLOW_PATH | REJECT_OR_HANDOFF
```

同时混合了语义识别、执行方式和回复外形，造成以下问题：

1. 知识直答与策略拒绝共用 `REJECT_OR_HANDOFF`，必须依赖 reasonCode 才能辨认；
2. 菜单、普通能力、工作流和 AGENT 都可能表现为 FAST_EXECUTE；
3. 固定多步骤和动态 Agent Loop 都被压成 SLOW_PATH；
4. 多意图、跨域或条件表达容易被无差别送入 Agent Loop；
5. 续办、取消和执行模式无法由一个稳定入口契约解释。

能力仲裁、补槽与任务形态既可以合并，也可能需要独立复核。拆分调用会增加时延和成本，并可能产生
结论冲突，但这应由预算、结果合并和 PolicyGate 治理，不应被固化成“入口最多调用一次模型”的
架构限制。

## 2. 决策

### 2.1 一次性替换 Decision

采用完整路由词表：

```text
DIRECT_KNOWLEDGE
NAVIGATION
EXECUTE_CAPABILITY
START_WORKFLOW
STATIC_PLAN
DELEGATE_GOAL
START_LOOP
RESUME_TASK
RESUME_LOOP
CLARIFY
CANCEL
REJECT
HANDOFF
```

不实现新旧双轨兼容层。所有 Agent、JSON Schema、缓存、数据库投影、指标、测试和模型输出在
同一个协调发布中迁移。发布前清空旧 Decision Cache，禁止新旧服务混跑。

### 2.2 入口职责拆分

```text
EntryRouteCoordinator
  -> ContinuationGate
       -> DeterministicContinuationRules
       -> ContinuationUnderstandingModel
       -> ContinuationPolicyGate
  -> IntentEvidenceBuilder
  -> DeterministicEntryRules
  -> TaskShapeModel
  -> LoopEntryPolicyGate
  -> RouteDispatcher
```

`DeterministicEntryRules` 优先。标准知识、菜单、唯一能力、缺槽、自治 Agent、显式工作流和完全
可表达的 IntentPlan 都不调用模型。只有规则灰区才调用 TaskShapeModel。

### 2.3 模型调用编排

简单灰区默认把能力仲裁、模型补槽和任务形态判断合并为一次结构化模型调用，以降低时延。复杂灰区
允许把候选仲裁、补槽、TaskShape 复核和 Schema 修复拆成多次调用。平台不设置“灰区最多一次模型
调用”的公共约束；每次调用必须声明用途，并分别受预算、超时、Schema、版本和观测约束。

多次模型结论不能直接覆盖前序结果。协调器只合并可追溯的候选、槽位和 TaskShape 证据，冲突或
越权输出交给 `LoopEntryPolicyGate` 收紧或降级。额外调用不得改变能力风险等级、ConfirmationPolicy、
资产状态或 Runtime 权威状态。

公共 RouteDecision 只暴露：

```text
Decision
RouteTarget
candidateIds
TaskShape
IntentPlan（仅 STATIC_PLAN）
missingSlots
confidence/reason/evidence/version
```

subGoals、dependsOn 和 SelectionBasis 只在 Router 内部使用，不进入 A2A，不持久化为执行计划。

### 2.4 Static Plan 与 Agent Loop

SelectionBasis 固定为：

```text
NOW | RESULT_RULE | AFTER_OBSERVATION
```

- NOW 和 RESULT_RULE 可以形成 STATIC_PLAN；
- AFTER_OBSERVATION 表示必须基于真实结果选择后续能力，是 START_LOOP 的核心证据；
- OPEN_ENDED_DIAGNOSIS 也可以进入 START_LOOP；
- 多意图、跨域、长句或“如果/然后”本身都不是 START_LOOP 的充分条件。

“工资没到账就检查工资卡状态”是 STATIC_PLAN；“工资没到账，帮我排查原因”才是 START_LOOP。

### 2.5 ConfirmationPolicy

CapabilityCard 增加：

```text
NONE | REVIEW_ONLY | EXPLICIT
```

规则：

- R0 默认 NONE，可收紧；
- R1 默认 EXPLICIT，可经能力声明使用 REVIEW_ONLY，不能 NONE；
- R2 强制 EXPLICIT，不能放宽；
- `cap.card.replace` 声明 REVIEW_ONLY。

REVIEW_ONLY 要求展示参数摘要并等待 `REVIEW_ACCEPT`；EXPLICIT 要求明确 `CONFIRMATION`。
两者完成前均不发幂等键、不调用 A2A、不执行领域副作用。

Loop 可见性按能力卡自动推导为 AUTO_READ_ONLY、PROPOSE_ONLY 或 DENY。开发者只声明例外，
且配置只能收紧，不能把高风险或有副作用能力放宽为自动执行。

### 2.6 本期不交付运营控制面

本决策不包含运营干预数据库、TTL、PIN、审批、RBAC 或控制台。入口只保留可扩展边界，不为
未交付控制面增加运行时语义。

### 2.7 续轮理解与状态保留分离

`ContinuationGate` 只识别当前输入与前台任务的关系。未完成任务期间出现新目标时，入口先询问
是否切换；用户接受后，各执行 Runtime 先保存自己的状态，再由平台公共的 `TaskContextManager`
原子地将旧任务焦点改为 `SUSPENDED`，并把新目标送回入口路由。旧任务的业务状态保持不变，
后续可以恢复。

Review、Confirm、取消、切换和恢复允许自然语言表达。结构化按钮事件和 Runtime 声明的精确槽值走确定性快路；
其余自然语言使用独立逻辑模型 `ContinuationUnderstandingModel`。该逻辑模型可以和 TaskShape/仲裁映射到同一个底层模型，
但提示词、JSON Schema、版本、指标和缓存必须隔离。
自然语言 R2 Confirm 由模型输出 `confirmationStrength=EXPLICIT_ACTION`，PolicyGate 只校验枚举、
目标引用、状态版本与 0.95 置信门槛，不维护自然语言短语表。

任务恢复不重放已完成动作，不自动接受 Review/Confirm，也不自动执行副作用。平台只记录 taskId、
routeTarget、对话焦点和 `runtimeType + runtimeRef`。Agent Loop 的 goal、status、facts、步骤、
pendingAction 和 iteration 只由 `AgentLoopRepository` 持有；平台只保存 loopId 引用，不复制或
反向覆盖 Loop 内部状态。

入口需要理解待交互内容时，通过只读 `RuntimeContinuationPort` 从原 Runtime 获取本轮临时摘要。
该摘要不持久化到平台记录；Loop 摘要由 Loop Runtime 从自己的权威记录生成。

切换询问本身由平台 `PendingSwitch` 记录，并绑定当前 taskId、focusVersion、sourceTurnId 和
newGoalSpan；该记录不包含 Loop 运行态，不能修改或覆盖 LoopRun。

### 2.8 模型优先与知识准入

自然语言续轮、指代、纠正、任务形态和 Loop 动作提案优先由各自的模型契约完成；Java 只验证事件、引用/版本、
白名单、置信度、风险和状态迁移，不维护自然语言短语表。知识补强只发生在离线：健康基线模型必须在至少两个自然改写上稳定失败，
且根因为 `DOMAIN_KNOWLEDGE_GAP`，补强项才能以 `EVALUATED_GAP` 注入 Prompt。模型/网络不可用、Schema/token、
PolicyGate、Runtime/A2A 或单次失败均不准入知识，也不在线上同一请求中补知识后重试。

## 3. 后果

### 正向

- 每个入口结论可以直接解释执行去向；
- 固定计划不再承担动态模型成本；
- 模型只处理语言和任务拓扑灰区；
- Review、Confirm、续办和取消成为显式契约；
- 新目标切换不会丢失旧任务，普通任务和 Loop 使用同一套焦点与恢复能力；
- Static Plan 与动态 Loop 拥有不同状态权威。

### 代价

- 一次性迁移会影响所有 Decision switch、Schema、缓存和测试；
- 新旧服务不能混跑，必须协调发布；
- TaskShape 模型输出和提示词需要新的专项评测；
- R1 默认确认会改变未声明能力的交互，资产发布前必须完成声明审计；
- 动态 Loop 增加独立状态机、持久化和模型契约；所有环境默认开启，由候选、权限、预算、风险和动作 Gate 限制真实执行。

## 4. 被否决方案

| 方案 | 否决理由 |
| --- | --- |
| 长期保留四出口 | 继续混淆知识、执行、计划、Loop 和拒绝语义 |
| 新旧 Decision 双轨 | 用户明确选择一次性替换；双轨会把复杂度长期留在 Runtime |
| 强制 TaskShapeModel 单独调用 | 所有请求都增加固定时延；是否拆分应由灰区复杂度和模型表现决定 |
| 强制灰区最多调用一次模型 | 无法支持独立复核、结构化修复和不同模型分工 |
| 按多意图无差别送入 Agent Loop | 固定计划被不必要模型化 |
| 只要读取结果就 Agent Loop | RESULT_RULE 只控制已知分支，应由 Static Plan 承担 |
| R1 默认跳过确认 | 有副作用能力缺少统一复核边界 |
| R1 一律显式确认且无例外 | 无法表达已审核的轻量参数 Review 场景 |
| 本期同时建设运营控制台 | 超出入口路由闭环范围，延迟核心契约落地 |

## 5. 验收

1. 旧四出口在代码、Schema、缓存和模型输出中全部消失；
2. “换卡”确定性返回 CLARIFY，不调用模型、不进入 Loop；
3. “信用卡”恢复换卡任务并进入 REVIEW_PENDING；
4. “继续”重新过槽位、主体、Schema 和 Guardrail 后才执行；
5. 固定条件计划返回 STATIC_PLAN + RESULT_RULE；
6. 开放式排查返回 START_LOOP + AFTER_OBSERVATION；
7. 确定性请求模型调用为零；灰区调用次数不作固定限制，但每次调用都能说明用途并落入请求预算与观测；
8. Review/Confirm 前没有幂等键或外部副作用；
9. 动态 Loop 总开关在开发、测试、生产均开启；符合条件时创建独立 LoopRun，依赖或策略失败时受控 HANDOFF；
10. 观测记录 Decision、TaskShape、reason、evidence 和版本，不记录模型思维过程。
11. 未完成换卡任务中提出账单查询时先询问切换，接受后换卡保持 CLARIFY_PENDING 并可恢复；
12. 恢复 REVIEW_PENDING/CONFIRM_PENDING 不得直接执行，R2 模糊自然语言不得生成 CONFIRM。
13. 平台只保存 loopId 引用和焦点；Loop 的 goal、facts、steps、pendingAction 和 iteration 只在
    AgentLoopRepository 中恢复。
14. PendingSwitch 的按钮与自然语言接受/拒绝共用 ContinuationPolicyGate；达到 3 个挂起任务时
    返回限制提示，不创建第 4 个挂起任务。
15. 模型 subGoals 的候选、依赖与 SelectionBasis 组合必须通过语义校验；非法组合回退规则仲裁。
16. RUNNING/IN_PROGRESS Runtime 未声明持久化完成回执能力时拒绝切换；PendingGoal 路由失败恢复旧焦点。
17. Loop 动作指纹由平台按规范化动作计算并复核，Planner 不能用自定义指纹绕过重复检测；展示文本不回灌 Planner。

## 6. 实现记录

- 公共契约与 Schema：`framework/contracts/agent-api`；
- 入口路由：`framework/runtime/agent-runtime-core/.../entry`；
- 续轮与平台焦点：`framework/runtime/task-orchestrator/.../continuation`、`.../context`；
- Static Plan：`StaticPlanCoordinator`，步骤、游标和参数快照的状态权威均为 IntentPlan Repository；
- Agent Loop：`AgentLoopStarter`、`AgentLoopCoordinator`、`JdbcAgentLoopRepository`；
- 数据迁移：Flyway V10 至 V17；V13/V14 分别补任务和 Static Plan 的版本 CAS，V15 补 Loop
  claim 恢复，V16 补 Static Plan Runtime 参数快照，V17 补 Loop confirmedSlots/pendingSlots；
- 模型资产：arbitration、continuation、loop-planner 三套独立 Prompt/Schema/指标用途。

实现命名说明：当前 `TaskShapeModel` 由升级后的 `ModelArbitrator` 默认合并承担；该实现可以演进为
多阶段模型编排，不改变公共 RouteDecision 和 PolicyGate 契约。`PlatformTaskRegistry + ConversationFocusManager` 由 `PlatformTaskContextManager`、
`TaskContextStore` 与 JDBC 实现共同承担；Loop 的 Context 构造、Observation 归一化、状态选择和
续轮恢复分别由 `LoopContextBuilder`、`LoopObservationNormalizer`、`LoopStateMachine`、
`LoopContinuationViewProvider` 和 `LoopResumeAdapter` 承担，最终状态由 `AgentLoopRepository`
的事务与版本 CAS 提交；`RouteDispatcher` 在完成穷尽映射后统一委托 `PlatformRuntimeBridge` 执行
reservation/binding/terminal close，不参与自然语言判断。

当前验证状态：全 Reactor、前端测试与构建、`tools/promptopt`、Loop JDBC `4/4`、平台焦点
JDBC `9/9`、核心 E2E `16/16`（跳过 `0`）、P0 `39/39`、P1 `12/12` 和入口 GOAL 两跳三任务
血缘均已通过；真实模型工资排查连续 `3/3` 形成 `CALL_CAPABILITY -> Observation -> FINISH`。
后端测试已使用独立 OpenSearch 索引，避免无向量测试模型覆盖生产索引。本 ADR
的本地交付和外部验收门禁已经闭环，但不得解读为“生产发布已经完成”。

上述实现不改变 2.6：运营干预、审批、PIN、RBAC 和运营控制台仍不在本期范围。
