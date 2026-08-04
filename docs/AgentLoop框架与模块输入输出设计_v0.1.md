# Agent Loop 框架与模块输入输出设计 v0.1

> 状态：代码与持久化主链已实现；开发、测试、生产运行开关均默认开启  
> 日期：2026-08-02  
> 关联：`入口意图路由实现与运营干预设计_v0.1.md`、`Agent平台总体架构草案_v0.7.md`、`ADR-010-入口路由Decision与任务形态判定.md`

## 1. 定位

Agent Loop 不是与知识、菜单、工作流、Agent 平级的第五种业务能力，而是一种动态执行方式。

```text
能力归属：LOCAL | A2A
执行方式：KNOWLEDGE | NAVIGATION | CAPABILITY | WORKFLOW | STATIC_PLAN | AGENT_LOOP
```

同一个领域 Agent 可以同时提供知识、菜单、固定工作流和动态 Agent Loop。

入口分工：

```text
EntryRouteCoordinator
  -> LoopEntryDecider
       -> DIRECT / STATIC_PLAN / DELEGATE_GOAL / START_LOOP / CLARIFY / HANDOFF

Decision=START_LOOP
  -> AgentLoopStarter
  -> AgentLoopCoordinator
```

`LoopEntryDecider` 只判断是否进入动态 Loop；`AgentLoopCoordinator` 才负责执行、观察和重规划。

## 2. Static Plan 与 Agent Loop 的边界

### 2.1 Static Plan

满足以下条件时使用 `Decision.STATIC_PLAN` 和现有 `IntentPlan`：

- 多个步骤已经全部识别；
- 每一步 capabilityId 已确定；
- 顺序、并行或条件关系可表达；
- 条件只控制一个已知动作是否执行；
- 不需要在执行后选择新的能力。

例：

```text
“工资没到账，就检查工资卡状态”

查询工资到账
  -> arrived=false 时查询工资卡状态

第二步 SelectionBasis=RESULT_RULE
Decision=STATIC_PLAN
```

### 2.2 Agent Loop

满足以下任一条件时才使用 `Decision.START_LOOP`：

- 后续能力必须读取真实结果后才能选择；
- 用户要求开放式分析、诊断或排查；
- 无法在执行前穷举所有合理分支；
- 固定计划无法表达完成条件。

例：

```text
“工资没到账，帮我排查原因”

先查询工资到账
  -> 读取真实结果
  -> 再决定查询卡状态、入账批次、发薪信息或结束

后续 SelectionBasis=AFTER_OBSERVATION
Decision=START_LOOP
```

复杂、多意图、跨域或包含“如果/然后”本身都不是进入 Loop 的充分条件。

## 3. 目标与非目标

### 3.1 目标

1. 实现 `Plan One Action -> Validate -> Execute -> Observe -> Replan`。
2. 知识、菜单、能力、工作流和 A2A Agent 通过受控 Action 接入。
3. 所有业务执行继续经过任务建档、护栏、确认和幂等。
4. 支持缺槽、Review、Confirm、取消、失败和重启续办；跨任务挂起和恢复复用平台
   `TaskContextManager`。
5. 支持并发防重、步骤预算、deadline、重复动作和 A2A 环路控制。
6. Postgres 是 Loop 状态权威，Workspace 只保存推理草稿。

### 3.2 非目标

- 不允许 DeepAgent 直接调用业务接口；
- 不用 Agent Loop 替代固定工作流；
- 不把模型自由文本当业务事实；
- 不把现有 `agent_intent_plan` 改造成动态追加计划；
- 本期不交付运营干预、审批、PIN 或控制台；
- 不依靠生产总开关降级；依赖、候选、权限、预算、风险与结果异常由 PolicyGate/HANDOFF 收口。

## 4. 总体执行链路

```text
RouteDecision(START_LOOP)
  -> AgentLoopStarter
  -> AgentLoopRepository.open
  -> LoopContextBuilder
  -> LoopCandidateRetriever
  -> AgentLoopPlanner.nextAction
  -> LoopActionValidator
  -> LoopActionPolicyGate
  -> AgentLoopRepository.claimAction（CAS）
  -> LoopActionExecutorRouter
       -> KnowledgeActionExecutor
       -> NavigationActionExecutor
       -> CapabilityActionExecutor
            -> AgentTaskExecutor -> TaskOrchestrator
            -> Local Port | A2A TASK | Workflow
       -> DelegationActionExecutor -> A2A GOAL
  -> LoopObservationNormalizer
  -> LoopStateMachine
  -> AgentLoopRepository.completeStepAndAdvance（CAS）
       -> Replan
       -> WAITING_USER / WAITING_REVIEW / WAITING_CONFIRMATION
       -> COMPLETED / FAILED / HANDED_OFF / EXPIRED / CANCELLED
  -> LoopResponseBridge
       -> ResponsePlanner.planRuntimeResponse
       -> ResponseRealizer -> AnswerAudit
```

一次循环只接受一个动作。合法动作先占领步骤，真实 Observation 落库后才能开始下一轮。

当前代码已经显式拆出 `LoopContextBuilder`、`LoopStateMachine`、
`LoopContinuationViewProvider` 和 `LoopResumeAdapter`。`LoopContinuationPort` 负责把后两者注册进统一
续轮端口，`AgentLoopStarter` 和 `DefaultAgentRuntime` 负责把 PolicyGate 已校验的状态版本贯穿到最终
CAS。`LoopObservationNormalizer` 将各执行器结果收敛为结构化 Observation。

## 5. 核心数据契约

### 5.1 LoopStartRequest

| 字段 | 来源 | 说明 |
| --- | --- | --- |
| `agentId/sessionId` | Runtime | 所属 Agent 和会话 |
| `traceId/rootTaskId` | Trace/A2A | 血缘 |
| `goal` | 用户原话或 A2A GOAL | 不进入普通日志 |
| `candidateIds` | RouteDecision | 当前允许候选 |
| `taskShape` | RouteDecision | OPEN_ENDED_DIAGNOSIS 或 OBSERVATION_DRIVEN |
| `contextLease` | Context Engine | 可信上下文租约 |
| `deadline` | 入口/A2A | 绝对期限，不得延长 |
| `lineage` | A2A | 委托路径和深度 |

输出：`LoopOutcome`。

### 5.2 LoopRun

```text
loopId
agentId
sessionId
rootTaskId
goal
status
iteration
maxIterations
confirmedSlots
facts
pendingAction
pendingSlots
deadline
version
ownerInstanceId
```

状态：

```text
NEW
RUNNING
WAITING_USER
WAITING_REVIEW
WAITING_CONFIRMATION
COMPLETED
FAILED
HANDED_OFF
EXPIRED
CANCELLED
```

### 5.3 LoopContext

提供给 Planner 的最小上下文：

```text
goal
confirmedSlots（带来源）
pendingSlots
facts（按 sourceId 命名空间）
lastObservation
conversationHistory（本 Agent 已裁剪的实际面客消息）
availableContext（带来源的只读证据）
candidates
availableActions
remainingBudget
channel/page
```

不得包含模型历史思维过程、明文主体引用、后端原始报文或无预算完整会话。`conversationHistory`
只帮助 Planner 理解用户与系统已经交流过什么，不是参数来源；参数仍只能来自 `confirmedSlots` 或
已提交 `facts`。

### 5.4 LoopAction

```text
SEARCH_KNOWLEDGE
RESOLVE_MENU
CALL_CAPABILITY
DELEGATE_GOAL
ASK_USER
FINISH
HANDOFF
```

公共字段：

```text
actionType
actionId（Runtime 分配）
proposalReasonCode
inputProvenance
```

模型不得生成 actionId、接口地址或候选之外的目标 ID。

### 5.5 ActionCheck

```text
verdict = PROCEED | WAIT_USER | WAIT_REVIEW | WAIT_CONFIRMATION | REJECT | HANDOFF
reasonCode
missingSlots
normalizedParameters
riskLevel
confirmationPolicy
hasSideEffects
```

### 5.6 LoopObservation

```text
status = SUCCESS | NEED_USER | PARTIAL | FAILED | CANCELLED
sourceType = KNOWLEDGE | MENU | CAPABILITY | WORKFLOW | AGENT
sourceId
facts
reasonCode
failureClass
taskId
delegationId
retryable
displayHints
```

Planner 只消费标准化 Observation，不直接消费各后端私有结构。`LoopContextBuilder` 会从 Planner
视图中移除 `displayHints`；展示文本只留给 `LoopResponseBridge`，不能回灌 Planner。终态 `FINISH`
产生的空 Observation 不覆盖最后一个有业务意义的 Observation。`LoopResponseBridge` 只把已经通过
领域模板或审批知识渲染进用户摘要的事实交给回复模型，模型上下文与用户可见结果使用同一投影。

### 5.7 LoopOutcome

```text
loopId
state
responsePhase
pendingQuestionOrReview
lastObservation
completedFacts
lastTaskId
reasonCode
```

## 6. 模块输入输出

| 模块 | 目标 | 输入 | 输出 |
| --- | --- | --- | --- |
| `AgentLoopStarter` | 根据 START_LOOP 开启或降级 | RouteDecision、ContextLease | LoopOutcome |
| `LoopContinuationViewProvider` | 从 Loop 权威记录生成只读续轮摘要 | loopId | ContinuationSnapshot |
| `LoopResumeAdapter` | 按平台引用加载 Loop 并映射续轮事件 | loopId、ContinuationEvent | StateTransition 或 LoopOutcome |
| `LoopContextBuilder` | 构建受预算上下文 | LoopRun、步骤事实、ContextLease | LoopContext |
| `LoopCandidateRetriever` | 提供本轮受控工具空间 | goal、facts、Agent、资产快照 | CandidateSet |
| `AgentLoopPlanner` | 只提出一个下一动作 | LoopContext | LoopAction |
| `LoopActionValidator` | 校验引用、Schema、来源和平台动作指纹 | LoopAction、CandidateSet | ValidatedAction |
| `LoopActionPolicyGate` | 风险、确认、权限和预算裁决 | ValidatedAction、CapabilityCard、主体 | ActionCheck |
| `LoopActionExecutorRouter` | 分派获准动作 | ValidatedAction、ActionCheck | 原始结果 |
| `LoopObservationNormalizer` | 统一异构结果 | 各执行器结果 | LoopObservation |
| `LoopStateMachine` | 决定合法状态迁移 | LoopRun、ActionCheck/Observation | StateTransition |
| `AgentLoopRepository` | 状态权威、占领和提交 | Run、Action、Observation、version | 最新 LoopRun |
| `LoopResponseBridge` | 投影可见事实并交给统一回复策略 | LoopOutcome、用户可见历史、channel | ResponsePlan、AgentResponse |

## 7. AgentLoopPlanner

Planner 使用单步 Proposal 模式：

```text
输入：目标、已确认参数、已完成事实、最近 Observation、候选、剩余预算
输出：唯一 LoopAction
```

DeepAgent 只注册 Proposal Tool：

```text
propose_knowledge_search
propose_menu_resolution
propose_capability
propose_agent_goal
propose_ask_user
propose_finish
propose_handoff
```

代码中 `ModelAgentLoopPlanner` 把提案生成委托给 `DeepAgentSingleActionPlanner`。后者位于既有
Slow Path 的 DeepAgent 依赖边界内，只向 DeepAgent 注册上述 Proposal Tool；Runtime Starter
不直接依赖 OpenJiuwen API。提案返回后仍由 `ModelAgentLoopPlanner` 按
`loop-action-proposal.schema.json` 重验，再交给 Runtime 的 Validator 和 PolicyGate。

Proposal Tool 只收集动作，不调用业务接口。目标型工具的 `targetId` 被限制为本轮候选；无目标
控制动作会清空 target、参数和参数来源；单轮第二个动作返回 `MULTIPLE_ACTIONS_PROPOSED`。
模型不提供可信指纹。`LoopActionFingerprint` 对 actionType、targetId 和规范化参数计算稳定摘要，
Validator 重新计算并常量时间比较；重复动作检测只使用这个平台生成的语义指纹。

规划提示词必须要求：

- 只能使用候选；
- 一次只提出一个动作；
- 参数必须有来源；
- 不把提案写成已执行；
- 不生成面客回复；
- 只能基于已落库 facts 判断完成；
- 找不到合法动作时 ASK_USER、HANDOFF 或 FINISH，不编造能力。

Planner 先使用模型本身能力。只有健康基线模型在至少两个自然改写上稳定失败，且确认为
`DOMAIN_KNOWLEDGE_GAP` 时，才通过离线评测将版本化知识标记为 `EVALUATED_GAP` 并注入 Prompt。模型不可用、Schema、
PolicyGate、Runtime/A2A 或 Observation 问题都不得通过补知识规避；线上不做失败后的知识重试。

## 8. LoopActionPolicyGate

### 8.1 EffectiveLoopAccess

平台按能力卡自动得到：

```text
AUTO_READ_ONLY | PROPOSE_ONLY | DENY
```

- 非 ACTIVE 或显式 `loopAccess=DENY` -> DENY；
- R0、无副作用、confirmationPolicy=NONE -> AUTO_READ_ONLY；
- 其余能力 -> PROPOSE_ONLY；
- 能力只配置例外，不能通过配置放宽平台推导结果。

### 8.2 自动执行

仅同时满足以下条件时输出 `PROCEED`：

- R0；
- 无副作用；
- confirmationPolicy=`NONE`；
- 必填参数完整且来源可信；
- 主体和认证等级满足能力卡；
- 条件为 PROCEED；
- deadline 和预算未到；
- 当前实例仍拥有会话；
- 相同动作指纹未重复。

### 8.3 Review 与 Confirm

| 条件 | Verdict | 状态 |
| --- | --- | --- |
| `confirmationPolicy=REVIEW_ONLY` 且未接受 | `WAIT_REVIEW` | `WAITING_REVIEW` |
| `confirmationPolicy=EXPLICIT` 且未确认 | `WAIT_CONFIRMATION` | `WAITING_CONFIRMATION` |

R1 默认 EXPLICIT，但能力可声明 REVIEW_ONLY。R2 强制 EXPLICIT。用户接受后，动作必须重新经过 Validator、PolicyGate、主体、Schema 和 Guardrail；不能直接使用旧提案执行。

Review/Confirm 回执必须绑定 `loopId + stepIndex + actionFingerprint + normalizedParametersHash`。
参数、能力、资产版本或动作指纹变化会使旧接受失效；校验一致且仍在 deadline 内时，PolicyGate 才能从
PROPOSE_ONLY 进入 PROCEED。

### 8.4 立即停止自动 Loop

遇到以下任一情况停止：

```text
缺槽
权限不足
条件不确定
PARTIAL
UNKNOWN_OUTCOME
FATAL
deadline
预算耗尽
重复动作
A2A 环路
```

## 9. 执行器边界

### 9.1 KnowledgeActionExecutor

返回审核知识证据和关联能力，不把模型生成文本当知识事实。

### 9.2 NavigationActionExecutor

通过 MenuCatalog 返回版本化 `NavigationAction`，禁止拼接 URL。

### 9.3 CapabilityActionExecutor

```text
CALL_CAPABILITY
  -> Arbitration/Route Decision 投影
  -> AgentTaskRequest(source=SLOW_PATH)
  -> AgentTaskExecutor
  -> TaskOrchestrator
  -> 建档、Guardrail、Idempotency
  -> Local Port | A2A TASK | Workflow
  -> TaskResult
```

`sourceInvocationId=loopId:stepIndex`，模型输出不得参与幂等键。

### 9.4 DelegationActionExecutor

只有目标仍需自行理解和规划时使用 A2A GOAL。已确定 capabilityId 时使用 A2A TASK。

委托深度默认最多 3；目标不得已出现在 delegation path；网络失败不得回落本地执行。

## 10. Observation 与事实

- 只保存已确认、可序列化的结构化字段；
- facts 按 knowledgeId/menuId/capabilityId/agentId 命名空间；
- 失败不删除此前成功事实；
- PARTIAL 保留已完成事实但停止自动推进；
- UNKNOWN_OUTCOME 禁止自动重试；
- 后端自由文本不直接回灌 Planner；
- 后端原始 `displayHints` 只进入回复桥，不直接作为事实进入 Planner；回复桥最终实际展示的文本、卡片和
  操作会写入 `ConversationTurn.messages`，下一轮再通过已裁剪 `conversationHistory` 对所有本地模型可见；
- 最终答复只能引用已提交 facts。

## 11. 持久化

`agent_intent_plan` 继续服务静态 `IntentPlan`。动态 Loop 使用独立表：

```sql
create table agent_loop_run (
    tenant_id          varchar(128) not null,
    loop_id            varchar(64) not null,
    agent_id           varchar(128) not null,
    session_id         varchar(128) not null,
    root_task_id       varchar(64),
    trace_id           varchar(128),
    goal               text not null,
    status             varchar(32) not null,
    iteration          int not null default 0,
    max_iterations     int not null,
    candidate_ids      jsonb not null default '[]',
    confirmed_slots    jsonb not null default '{}',
    facts              jsonb not null default '{}',
    pending_action     jsonb,
    pending_slots      jsonb not null default '[]',
    deadline           timestamptz,
    version            bigint not null default 0,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    primary key(tenant_id, agent_id, loop_id)
);

create table agent_loop_step (
    tenant_id          varchar(128) not null,
    agent_id           varchar(128) not null,
    loop_id            varchar(64) not null,
    step_index         int not null,
    action             jsonb not null,
    task_id            varchar(64),
    delegation_id      varchar(64),
    status             varchar(32) not null,
    observation        jsonb,
    reason_code        varchar(128),
    created_at         timestamptz not null default now(),
    completed_at       timestamptz,
    primary key(tenant_id, agent_id, loop_id, step_index),
    foreign key(tenant_id, agent_id, loop_id)
      references agent_loop_run(tenant_id, agent_id, loop_id)
);
```

生产落地前必须补 goal、action 和 observation 的字段级加密/脱敏策略。

跨任务焦点不由 `agent_loop_run.status` 或本表唯一索引表达。平台任务记录保存
`runtimeType=AGENT_LOOP` 和 `runtimeRef=loopId`，平台 `ConversationFocusFrame` 保证一个会话只有
一个 `FOREGROUND`。被切走的 Loop 只在平台焦点记录中标记为 `SUSPENDED`，其 LoopRun 保持原业务
状态。Loop 的 goal、status、confirmedSlots、pendingSlots、facts、pendingAction、iteration 和步骤
游标仍以本表为唯一权威，不复制到平台记录。

## 12. 并发与恢复

### 12.1 跨任务挂起与恢复

`ContinuationGate` 位于入口链，不属于 Agent Loop。它判断用户是在继续当前 Loop、提出新目标，
还是恢复已挂起任务；自然语言灰区由独立的 `ContinuationUnderstandingModel` 处理。模型可以与
入口仲裁使用同一个底层模型，但提示词、Schema、版本、指标和缓存独立。

当用户确认切换目标时，Loop Runtime 先提交自己的当前 run/step 状态；随后
`TaskContextManager` 只改变平台对话焦点和保存 loopId 引用，不改变 `LoopRun.status`，也不保存
pendingAction 摘要。恢复时由 `LoopResumeAdapter` 按 loopId 重新加载权威 LoopRun：

- 执行前缺槽进入 `WAITING_USER` 时，run 保留待补槽名与原 PROPOSED 动作；收到合法槽位后取消
  原 Proposal、推进 iteration、清除 pendingAction，再基于已确认槽位重新规划，原动作不得直接执行；
- 执行器返回 `NEED_USER` 时，当前步骤已提交为 COMPLETED 且 pendingAction 为空；收到合法槽位后
  保留已完成步骤、合并 confirmedSlots，不再次推进 iteration，再从下一轮继续；
- `WAITING_REVIEW` 继续等待 Review，不视为已经接受；
- `WAITING_CONFIRMATION` 继续等待明确 Confirm；
- 已提交 Observation 和已完成步骤不重放；
- 能力或资产版本不兼容时重新校验、重新规划或重新确认，不能直接执行旧 pendingAction。

系统可以提示存在可恢复任务，但不得自动恢复后立即执行。

结构化按钮携带 `runtimeRef + stateVersion`，自然语言事件由续轮模型和 PolicyGate 解析后也绑定同一
快照版本。`LoopResumeAdapter` 必须使用该版本完成恢复或取消 CAS；版本过期、事件与等待状态不匹配、
槽位不在 pendingSlots 白名单时一律拒绝，不能重新读取最新版本后继续执行。

入口需要理解当前 Loop 待交互内容时，通过 `LoopContinuationViewProvider` 临时读取 LoopRun 并返回
受控 `ContinuationSnapshot`。该快照只用于本轮理解，不写入平台任务记录，不能反向覆盖 LoopRun。

### 12.2 执行步骤恢复

`claimAction`：

- 用 `loopId + stepIndex + version` 做 CAS；
- 插入 `EXECUTING` 步骤；
- 只有占领成功的请求可以调用外部执行器。

`completeStepAndAdvance`：

- 校验步骤仍为 EXECUTING；
- 保存 Observation；
- 合并 facts；
- 推进 iteration 和 version；
- 在一个事务中提交。

进程在执行中崩溃：

- Capability 用相同 `sourceInvocationId` 重放；
- A2A 用相同 delegationId 查询或重放；
- 知识和菜单只读动作可重试；
- 无法确认的副作用结果进入 UNKNOWN_OUTCOME。

## 13. 配置

```yaml
huawei.finance.agent.loop:
  enabled: false
  unavailable-mode: CLARIFY_THEN_HANDOFF
  execution-mode: CONFIRM_EACH # DISABLED | CONFIRM_EACH | AUTO_READ_ONLY
  max-iterations: 6
  max-model-calls: 8
  max-candidates: 8
  max-delegation-depth: 3
  max-repeat-action: 1
  deadline-seconds: 30
  workspace-path: ""
```

入口路由先上线时 `enabled=false`。Router 可以识别开放目标，但 PolicyGate 按安全策略收紧为
`CLARIFY/HANDOFF`；启用后由 `AgentLoopStarter` 创建独立 LoopRun。不得把开放目标硬转成静态计划。

## 14. 验收场景

主场景：

```text
用户：工资没到账，帮我排查原因。
```

预期：

```text
START_LOOP
  -> CALL_CAPABILITY(cap.payroll.arrival.query)
  -> Observation(arrived=false)
  -> Replan
  -> 根据真实结果选择下一合法动作
  -> Observation
  -> FINISH 或 ASK_USER/HANDOFF
```

必须验证：

1. 固定“没到账就查卡”不进入 Loop；
2. Planner 编造能力不执行；
3. 两个并发请求只能一个占领步骤；
4. 重启后从已提交 Observation 恢复；
5. 相同动作超过阈值停止；
6. R1 REVIEW/EXPLICIT 和 R2 EXPLICIT 正确挂起；
7. Review/Confirm 前无幂等键和外部调用；
8. 取消后 pendingAction 不再执行；
9. deadline 到期不生成下一步幂等键；
10. A2A 不形成环路；
11. 日志不出现完整目标、模型思维过程和主体引用；
12. 最终答复不引用未执行提案。

## 15. 实现映射与完成定义

| 模块 | 当前实现 |
| --- | --- |
| Starter/Coordinator | `AgentLoopStarter`、`AgentLoopCoordinator` |
| Context/Candidate/Planner | `LoopContextBuilder`、`LoopContext`、`LoopCandidateRetriever`、`ModelAgentLoopPlanner` -> `DeepAgentSingleActionPlanner`；Proposal Tool 保留稳定语义前缀、按规划轮生成唯一 ID，并在本轮结束后注销 |
| Validator/PolicyGate | `LoopActionValidator`、`LoopActionPolicyGate` |
| Executor/Observation | `LoopActionExecutorRouter`、`LoopObservationNormalizer`、结构化 `Observation` |
| 状态与恢复 | `LoopStateMachine`、`JdbcAgentLoopRepository`、`LoopContinuationViewProvider`、`LoopResumeAdapter`、`LoopContinuationPort` |
| 面客桥接 | `LoopResponseBridge` -> `ResponsePlanner.planRuntimeResponse` -> `ResponseRealizer` -> `AnswerAudit`；禁止输出 completedFacts 原始 Map |
| 契约与 Prompt | `loop-action-proposal.schema.json`、`loop-planner-skill` |

Loop 状态只存在 `agent_loop_run/agent_loop_step`；平台任务表只绑定 `runtimeRef=loopId`。

动态 Agent Loop 真正完成必须同时满足：

1. 每轮只产生一个受控动作；
2. 动作经过 Validator 和 PolicyGate；
3. 执行前占领，执行结果原子落库；
4. 下一轮读取真实 Observation；
5. 缺槽、Review、Confirm、取消、失败和重启都有唯一状态；
6. 模型、Workspace 和观测系统都不是执行状态权威；
7. 知识、菜单、工作流和 A2A Agent 均通过受控适配器接入。
8. FINAL/REVIEW/CONFIRM/CLARIFY 均解析同一份 ResponsePolicy，回复模型只看到已经进入用户可见摘要的提交事实。

## 16. 当前验证状态

截至 2026-08-02，当前代码的全 Reactor、Loop Coordinator 与支撑模块单元测试已经通过。V17
增加 `confirmed_slots/pending_slots`；真实 PostgreSQL/Redis 下，`JdbcAgentLoopRepositoryTest`
`4/4`、`JdbcTaskContextStoreTest` `9/9` 通过，覆盖 `loopId + stepIndex + version` CAS、两类
WAITING_USER 恢复、claimed-step 重启恢复、槽位持久化和唯一前台。最新镜像已通过核心入口与
开放排查 E2E `16/16`（跳过 `0`）、Compose P0 `39/39` 和 P1 `12/12`；真实模型工资排查
连续 `3/3` 形成 `CALL_CAPABILITY(SUCCESS) -> FINISH(SUCCESS)`。`loop-planner-v5` 明确禁止
为遍历候选而继续动作，并要求 facts 足以回答目标时立即结束。后端 E2E 使用独立
OpenSearch 索引，不会覆盖生产语义向量。开发、测试、生产均断言 `agent.loop.enabled=true`；
总开关开启不代表任意能力可被 Planner 使用，候选仍必须通过 `EffectiveLoopAccess` 和动作策略门禁。
