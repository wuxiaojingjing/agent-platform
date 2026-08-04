# openJiuwen 金融面客智能体解决方案总体设计与重构计划 v0.1

> 状态：目标架构与社区合入设计
>
> 范围：面向金融行业的面客智能体基础能力，手机银行作为首个参考案例
>
> 基线：当前工程实现见[《Agent 平台总体架构草案 v0.7》](Agent平台总体架构草案_v0.7.md)。本文件描述目标标准，不以兼容当前目录和模块为前提。


## 1. 结论与定位

本项目的社区目标不是提交一套特定银行的手机银行应用，也不是为现有工程目录制作一层适配壳，
而是在 openJiuwen `agent-solution/finance` 下建设可复用的“金融面客智能体解决方案”。

该解决方案提供金融面客场景所需的基本能力、稳定契约、默认实现、组件化装配机制和测试规范。
手机银行只作为第一个完整案例，用于证明主智能体、业务子智能体、知识平台、安全能力和行内系统
能够按照统一范式组合，不成为基础框架的反向依赖。

目标关系为：

```text
openJiuwen Core
  -> agent-runtime-java
  -> agent-solution/finance/customer-facing-agent（金融面客智能体）
       -> 通用契约、四类引擎、治理、目录、Starter、Testkit
       -> 可选 Connector
       -> examples/mobile-banking（手机银行案例）
```

openJiuwen 金融方案负责构建基本能力。银行人工智能实验室负责引入、发行和行内适配，但不重复建设
社区已有的对话、意图、执行、知识接入与治理框架。

## 2. 设计原则

1. **标准优先**：目标是形成可合入 openJiuwen 的标准能力，不固化当前仓库的历史模块边界。
2. **面客为边界**：覆盖手机银行、远程银行、财富顾问、企业银行等面客渠道，不扩张为整个金融 IT 平台。
3. **手机银行是案例**：案例可以完整，基础模块不得依赖案例代码、资产或配置。
4. **单一运行语义**：所有 GOAL/TASK 入站统一经过目标 Agent Runtime，不存在领域直通执行链。
5. **单一目录真值**：Agent、能力和部署实例分别建模，由统一快照投影给控制台、网关和客户端。
6. **组件化而非模板复制**：二开方通过资产、SPI、Skill、Workflow、Connector 或引擎替换完成扩展。
7. **安全顺序不可绕过**：主体、权限、领域安全、确认、幂等、审计由 Runtime 固定编排。
8. **社区与行内解耦**：通用契约和默认能力进入社区；行内中间件、机构配置和安全实现留在企业扩展。

## 3. openJiuwen 复用与新增边界

本地 openJiuwen 源码已经分为 `agent-core-java`、`agent-runtime-java` 和 `agent-solution`。Finance
不应复制 Core 和 Runtime，而应以扩展模块、行业契约、策略和案例补齐金融面客能力。

| openJiuwen 层次 | 直接复用 | Finance 新增或增强 |
| --- | --- | --- |
| `agent-core-java` | ReActAgent、WorkflowAgent、图执行、组件、Tool/MCP、上下文、Session、Memory、Store、模型调用 | 金融意图证据与策略、面客连续交互策略、金融治理组件和领域扩展 |
| `agent-runtime-java` | HTTP/SSE 入站、`ServeOrchestrator`、`AgentHandler`、生命周期、探针、TaskStore、标准 A2A Card/JSON-RPC 和远端委托 | Finance Handler/Adapter、金融主体与安全上下文投影、行业观测字段 |
| `agent-solution/common` | Core/Runtime 扩展工程形态、Agent 实现和 Example 组织方式 | 可复用的 Finance 扩展只在证明跨行业通用后再上收 common |
| `agent-solution/finance` | 现有 methodology、skills、mcp 资产分层 | `customer-facing-agent` 基础能力与 `mobile-banking` 案例 |

具体复用原则：

- 对话管理复用 Core 的 Session、Context、Memory 和多 Workflow 切换能力，Finance 增加面客焦点、
  连续意图、执行授权隔离和回复治理；
- 意图引擎优先复用 Core 的意图组件、Workflow、模型和检索能力，Finance 增加金融候选契约、证据、
  风险边界、领域路由和评测标准；
- 执行引擎复用图执行、中断恢复、Tool、Workflow、TaskStore 和 Runtime Orchestrator，Finance 增加金融
  Task/Plan 治理、确认、幂等、领域安全和审计语义；
- 知识接入复用 Retrieval、Store、Tool 和 MCP，Finance 增加知识证据、引用、权限、时效和领域过滤标准；
- A2A 优先适配 `agent-runtime-java` 已采用的标准 Agent Card 和 JSON-RPC。当前工程的
  `DelegationEnvelope/Receipt` 只保留其中金融必需的主体、deadline、幂等和审计语义，通过标准协议
  metadata 或经社区评审的扩展契约承载，不另建重复 A2A 平台；
- 只有确认属于 openJiuwen Core 或 Runtime 的通用缺口，才向对应仓库贡献；其余能力放在
  `agent-solution/finance/customer-facing-agent`。

## 4. 总体架构

面客智能体按对话管理、意图引擎、执行引擎和知识接入四个核心部分拆分，并由治理和目录能力横向约束。

```text
渠道 / 面客应用
      |
      v
+----------------------- 面客智能体 Runtime -----------------------+
|  对话管理                                                   |
|  会话 / 上下文 / 连续意图 / 焦点 / Agent Loop / 回复组织       |
|       |                                                     |
|       v                                                     |
|  意图引擎                                                   |
|  确定性入口 / 意图识别组件 / Slow-path Reasoning Loop         |
|       |                                                     |
|       v                                                     |
|  执行引擎                                                   |
|  Task / Static Plan / Workflow / A2A / 幂等 / 补偿 / 状态机   |
|       |                                                     |
|       +-------------------> 知识接入                         |
|                              Query / Evidence / Citation / SPI|
+----------------------------------------------------------------+
      |                    |                      |
      v                    v                      v
  业务子 Agent         领域安全能力           行内业务系统/知识平台

横切能力：Agent Catalog、主体与权限、确认、审计、可观测性、策略门禁
```

### 4.1 对话管理

对话管理负责用户与面客智能体之间的连续交互，不承担领域交易执行：

- 会话、消息和多轮历史；
- 全局上下文、会话记忆、工作记忆和受控上下文投影；
- 续办、取消、恢复、新目标切换及前台焦点；
- Static Plan、Workflow、Agent Loop 的交互状态呈现；
- 回复计划、模板/模型实现和最终面客信封；
- 使用过的历史、事实引用和上下文消费证据。

任务真值仍由相应执行 Runtime 保存。对话文本可以帮助理解，但不产生执行授权。

### 4.2 意图引擎

意图引擎负责从受控上下文和当前输入中得到候选、证据、目标和任务形态。内部不是简单的
Fast Path/Slow Path 二分，而是三层：

```text
确定性入口
  规则 / 按钮 / 缓存 / 明确事件
        |
        v
EnsembleIntentRecognizer（普通意图组件）
  多路召回 -> 融合 -> 仲裁 -> 直接输出 IntentResolution
        |
        | 无法可靠决策、目标开放或需要多步取证
        v
IntentReasoningLoop（真正的 Slow Path）
  大模型 Planner -> 识别工具 -> Observation -> Replan -> Finish
        |
        v
统一输出 IntentResolution / Clarify / Handoff
```

`EnsembleIntentRecognizer` 包含关键词、BM25、向量、规则、外部存量意图系统等多路召回，以及候选
融合和模型/规则仲裁。它虽然比确定性规则复杂，但仍是一次有界的意图识别组件，能够直接输出意图，
不应称为 Slow Path。

真正的 Slow Path 使用更大的模型，并复用当前 Agent Loop 的执行范式。它可以分多步调用能力目录、
知识证据、受控上下文、外部意图 Provider 等“识别工具”，观察结果后继续推理，直到输出结构化意图、
澄清或人工转接。它不能调用交易或业务执行工具，不能创建业务幂等键，也不能直接修改任务状态。

普通意图组件和 Slow Path 使用同一个稳定输出，例如：

```text
IntentResolution
  intents
  candidates
  evidence
  confidence
  taskShape
  decision
  reasonCode
```

因此，单意图、多意图和任务形态仍由意图引擎识别；执行引擎在接收 `IntentResolution` 后创建
Task、Static Plan、Workflow 或 Execution Agent Loop。

“把意图识别做成 Skill”是支持形式之一，不是唯一解。标准必须同时支持：

| 扩展形式 | 适用场景 |
| --- | --- |
| 资产配置 | 标准问、扩展问、同义词、规则、Prompt、阈值和边界 |
| SPI Provider | 接入行内意图平台、重排器、领域解析器或自研模型 |
| Skill / Tool | 可组合、可被规划选择的局部意图能力 |
| Workflow | 有明确节点、审批和运营流程的识别链 |
| 引擎替换 | 行内整体接管 IntentEngine，但继续遵守公共契约和治理门禁 |

必须区分两类 Agent Loop：

| Loop | 所属模块 | 允许调用 | 最终输出 |
| --- | --- | --- | --- |
| `IntentReasoningLoop` | 意图引擎 Slow Path | 候选、知识、上下文和外部意图识别工具 | `IntentResolution / Clarify / Handoff` |
| `ExecutionAgentLoop` | 执行引擎 | 通过治理门禁的业务能力、Workflow 或下级 Agent | TaskResult、事实或受控失败 |

### 4.3 执行引擎

执行引擎负责把已经形成的决策变成可审计任务：

- 普通 Task、Static Plan、Workflow 和 Agent Loop；
- 能力参数校验、缺槽、确认、幂等、超时和取消；
- 本地业务 Port 调用与跨 Agent A2A 委托；
- 任务状态、结果、补偿、重放和恢复；
- 通用安全检查与领域安全 Provider 调用；
- 结构化结果到对话回复计划的投影。

TASK 可以跳过重复意图识别，但不能跳过目标上下文、主体、任务建档、领域安全、护栏和幂等。

### 4.4 知识接入

社区侧提供知识接入标准，不在 Finance Solution 内重建客户知识平台：

- `KnowledgeQuery`、`KnowledgeEvidence`、`Citation` 和过滤条件；
- 知识召回、问答、文档检索和知识动作的统一 SPI；
- 权限、租户、领域、时效、来源和版本元数据；
- 检索证据到意图召回、回复生成和 Agent Loop 的受控投影；
- 连接器 TCK、降级语义和可观测规范。

上海三部建设的知识平台通过 `KnowledgeProvider` 接入。其索引、发布、运营和存储模型仍归知识平台所有。

### 4.5 治理与安全

安全分成两层：

```text
网关通用安全
  -> 面客 Runtime 识别目标领域
  -> 执行引擎调用对应 DomainSecurityProvider
  -> 确认 / 幂等 / 审计
  -> 业务能力执行
```

通用安全和领域安全能力由人工智能实验室提供实现；社区提供 SPI、调用顺序、失败语义、审计模型和 TCK。
北研一部负责手机银行侧的调用集成。业务扩展不得重排或绕过该顺序。

## 5. 统一目录模型

逻辑资产、运行实例和调用路由必须分离：

```text
AssetLoader --------------------> LogicalAgent / CapabilityDefinition
DiscoveryProvider（如 Nacos） ---> DeploymentInstance
                                  |
                                  v
                         AgentCatalogSnapshot
                                  |
                    +-------------+-------------+
                    v             v             v
                 控制台        A2A Gateway    Client/Planner
```

- `AssetLoader` 是 Agent 与能力定义的唯一解析和校验入口；
- `DiscoveryProvider` 只报告部署实例、协议、健康度和地址，不成为能力定义真值；
- `RouteResolver` 根据逻辑能力和健康实例选取调用目标；
- 控制台、Gateway 和 Client 共享 `AgentCatalogSnapshot`，不得各自解析 YAML；
- 配置状态、实现状态、部署状态和健康状态使用不同字段，不压成一个 `status`。

## 6. 行内二开与组件组装

行内二开不通过复制整套 Runtime 完成。标准装配优先级为：

```text
业务显式实现
  > 行内平台适配实现
  > Finance 默认实现
  > 安全降级实现
```

支持的组装粒度包括：

1. 只使用资产，采用全部默认引擎；
2. 替换一个 Provider，例如意图召回或知识平台；
3. 插入多个有序 Contributor，例如候选增强、响应增强和领域护栏；
4. 注册 Skill、Tool、Workflow 或业务子 Agent；
5. 替换整个引擎，但继续使用公共 API、治理链和 TCK；
6. 通过 Connector 对接行内模型、缓存、注册中心、消息、知识和安全中间件。

扩展点分为单实现替换型、多实现链型、按 key 注册型、事件订阅型和外部 Connector 型，不能统一依赖
`@ConditionalOnMissingBean`。每个扩展点必须声明覆盖规则、顺序、失败语义和是否允许降级。

## 7. 目标工程结构

建议在 openJiuwen 中采用以下逻辑结构；最终目录名遵循社区仓库约定，但层次和依赖边界保持不变：

```text
agent-solution/
└── finance/
    ├── methodology/                        # 已有金融方法论
    ├── skills/                             # 已有金融 Skill 资产
    ├── mcp/                                # 已有金融 MCP 资产
    └── customer-facing-agent/              # 面客智能体
        ├── api/
        ├── engines/
        │   ├── conversation/
        │   ├── intent/
        │   │   ├── deterministic/          # 规则、事件、按钮、缓存快路
        │   │   ├── recognizer/             # 多路召回、融合和仲裁
        │   │   └── reasoning-loop/         # 大模型 Intent Slow Path
        │   ├── execution/
        │   │   ├── task/
        │   │   ├── static-plan/
        │   │   ├── workflow/
        │   │   └── agent-loop/             # 业务执行 Loop
        │   └── knowledge/
        ├── governance/
        ├── catalog/
        ├── starter/
        ├── testkit/
        ├── connectors/
        │   ├── openjiuwen-runtime/
        │   ├── external-workflow/
        │   └── knowledge/
        └── examples/
            └── mobile-banking/             # 手机银行案例
                ├── app/
                ├── agents/
                ├── assets/
                ├── eval/
                ├── deploy/
                └── frontend/
```

`intent` 和 `execution` 下的子目录表达内部组件边界，第一阶段不拆成独立发布制品；对外仍分别是一个
Intent 制品和一个 Execution 制品，避免使用方绑定内部实现。

不得新增名为 `solution-common` 的兜底模块：

- 稳定公共契约进入 `api`；
- 跨引擎安全与审计进入 `governance`；
- Agent/能力目录进入 `catalog`；
- 与金融无关的通用能力应进入 openJiuwen Core；
- 只服务手机银行的公共组件放在案例内部，不发布为社区基础包。

## 8. 发布制品

第一阶段发布 8 个基础制品：

| 制品 | 说明 |
| --- | --- |
| `openjiuwen-finance-customer-facing-bom` | 统一版本和兼容组合 |
| `openjiuwen-finance-customer-facing-api` | 稳定契约、SPI 和扩展元数据 |
| `openjiuwen-finance-customer-facing-conversation` | 基于 Core Session/Context/Memory 的面客对话策略与扩展 |
| `openjiuwen-finance-customer-facing-intent` | 确定性入口、组合式意图识别器、Slow-path IntentReasoningLoop 和评测规范 |
| `openjiuwen-finance-customer-facing-execution` | 基于 Core/Runtime 执行能力的金融确认、幂等、安全和审计扩展 |
| `openjiuwen-finance-customer-facing-knowledge` | 基于 Retrieval/Store/MCP 的金融知识契约与接入策略 |
| `openjiuwen-finance-customer-facing-starter` | 默认组合与自动装配 |
| `openjiuwen-finance-customer-facing-testkit` | TCK、夹具和扩展测试工具 |

A2A 入站和远端委托直接复用 `agent-runtime-java`，Finance 只在确有契约投影需要时提供薄 Runtime Adapter，
不发布第二套 A2A 平台。Nacos、OpenSearch、外部工作流及行内中间件作为可选 Connector，不进入默认 Starter。
`mobile-banking` 默认作为源码案例，不被基础制品依赖；确需发布时使用独立 example 坐标。

## 9. 手机银行参考案例

手机银行案例用于展示，而不是定义框架。案例至少覆盖：

- 手机银行渠道接入面客主 Agent；
- 主 Agent 与账户、转账、信用卡、财富等业务子 Agent 协同；
- 配置、SPI、Skill 和 Workflow 四种扩展方式；
- 通用安全与领域安全串联；
- 上海知识平台的标准 Provider 接入；
- 行内中间件 Connector 覆盖默认实现；
- 北研四部维护意图资产、工作流、评测和数据飞轮；
- 业务开发只实现业务 Port、子 Agent、资产及必要扩展；
- 业务人员通过受控资产和审批流程参与运营。

未来可以平行增加远程银行、财富顾问和企业银行案例，不能复制四类引擎。

## 10. 组织职责

| 参与方 | 主要责任 | 不负责 |
| --- | --- | --- |
| 我方 / openJiuwen 社区 | Finance API、四类引擎、治理框架、Starter、Testkit、标准案例 | 客户私有中间件和机构业务规则 |
| 人工智能实验室 | 框架引入、版本治理、企业发行版、行内 Connector、安全能力实现 | 重写社区已有基础引擎 |
| 北研一部 | 手机银行入口、渠道接入、公共业务组件、领域安全调用、开发范式 | 安全能力自身实现 |
| 北研四部 | 意图资产、工作流搭建、效果调优、评测体系、数据飞轮 | 修改 Runtime 安全顺序 |
| 上海三部 | 知识平台、知识治理、`KnowledgeProvider` 实现 | 面客 Runtime 和交易中控 |
| 业务开发 | 业务子 Agent、Skill、Workflow、业务 Port 和领域资产 | 复制平台中控或直写其他 Agent 状态 |
| 业务人员 | 业务规则、标准问、验收样例、运营配置和发布审批 | 直接修改执行代码和安全策略 |

## 11. 当前实现审查与目标处理

当前工程可以作为行为基线和手机银行案例来源，但不直接作为社区目录模板。

| 当前实现 | 问题 | 目标处理 |
| --- | --- | --- |
| `DomainAgentNode -> DomainAgentExecutor` | 与 Runtime-backed 入站形成双执行链，护栏和状态映射可能分叉 | 移出生产装配，仅保留兼容测试用途 |
| `RuntimeBackedAgentNode` | 当前工程中已符合统一 Runtime 方向 | 迁移期作为唯一入站；社区终态适配 openJiuwen ServeOrchestrator |
| `AssetLoader`、`AgentCardProjector`、`ConfiguredAgentCatalog` | 重复解析同一资产，失败策略不一致 | 统一为 `AgentCatalogSnapshot` |
| `NacosAgentDirectory` | 同时承担实例发现、能力目录和路由 | 拆为 `DiscoveryProvider` 与 `RouteResolver` |
| `intent-fastpath` | 实际包含多路召回、融合和仲裁，名称容易误导 | 拆为确定性入口与 `EnsembleIntentRecognizer` 组件 |
| `intent-slowpath` | 已有 ReAct/DeepAgent 规划雏形，但混有计划条件等职责 | 演进为大模型 `IntentReasoningLoop`；执行职责迁出 |
| 多个 `DomainAgents` | 成功、缺槽、失败和幂等结果构造复制 | 提供 Finance SDK 结果工厂 |
| 多个领域 HTTP 配置 | 超时、客户端、Map 解析和错误映射复制 | 提供受控 Remote Client Factory，领域保留 DTO |
| `intent-engine-default` 等聚合壳 | 发布层次偏碎、依赖关系不清 | 合入四类引擎制品和统一 Starter |
| `capability-registry` | 实际是检索索引模型，名称和粒度不准确 | 归入意图/知识索引 API 或重命名 |

以下模型边界应保留：`PlatformTask`、`TaskRecord`、`UnifiedTask`、`AgentTaskRequest/Outcome`、
`DelegationEnvelope/Receipt`。它们描述不同边界，不能为了减少类数量压成一个大 Task。

## 12. 重构实施计划

重构采用“行为冻结 -> 语义收敛 -> 模块迁移 -> 案例提炼 -> 社区合入”的顺序，不先做大规模目录移动。

### R0：基线冻结

- 固化当前关键用例、公开契约、原因码和数据迁移基线；
- 建立模块依赖图、API 基线和架构规则；
- 区分当前实现文档与目标架构文档；
- 禁止重构期间同时引入新的执行路径。

门禁：当前 Reactor、核心 E2E、A2A、上下文、确认、幂等和安全回归通过。

### R1：契约与目录收敛

- 建立面客智能体 API，明确四类引擎输入输出和扩展点；
- 引入 `AgentCatalogSnapshot`；
- 资产只经过一个结构化加载、Schema 校验和投影流程；
- 将 Nacos 收敛为部署实例 Provider，将路由算法移入 `RouteResolver`；
- 控制台、Gateway 和 Client 切换到统一目录。

门禁：同一资产只解析一次，配置状态与运行状态可独立观测。

### R2：Runtime 单路径

- 生产 A2A 只装配 `RuntimeBackedAgentNode`；
- GOAL 和 TASK 都经过目标上下文、主体解析、任务建档、领域安全、护栏和幂等；
- `DomainAgentNode/DomainAgentExecutor/TechDomainNodeFactory` 移入兼容或 Testkit；
- 统一 TaskResult 到 A2A 回执的边界映射和 reasonCode；
- 增加架构测试，禁止生产 Host 引入直通节点。

上述是当前工程的过渡收敛。进入 openJiuwen 后，目标调用链为
`A2AProtocolAdapter -> ServeOrchestrator -> Finance AgentHandler -> Core Runner`，不得把现有独立
Gateway 和自定义协议栈整体复制到 `agent-solution`。

门禁：同一能力通过本地、A2A TASK 和 A2A GOAL 的治理语义一致。

### R3：四类引擎归位

- `context-engine`、续轮、焦点、回复和 Loop 交互归入 conversation；
- 当前 fastpath 中的规则/事件快路拆为 deterministic entry，多路召回、融合和仲裁归入
  `EnsembleIntentRecognizer`；
- 当前 slowpath 中的 ReAct/DeepAgent 能力演进为使用更大模型的 `IntentReasoningLoop`，仅输出意图；
- task-orchestrator、Static Plan、Workflow、`ExecutionAgentLoop` 和 A2A 调用归入 execution；
- 知识查询、证据、引用和 Provider 归入 knowledge；
- 治理与目录使用明确模块，不建立 common 杂物包。

门禁：四类引擎只能依赖 API 和明确的下层端口，不能依赖手机银行案例。
`IntentReasoningLoop` 的工具白名单不得包含业务执行能力；`ExecutionAgentLoop` 不得重新解释用户原始意图。

### R4：组件化 SDK 与行内二开

- 提供 BOM、Starter、Testkit 和 Agent 模板；
- 提供 `TaskResults`、Remote Client Factory 和标准错误映射；
- 定义 Provider、Contributor、Registry、Event 和 Connector 五类装配规则；
- 建立安全、知识、意图、工作流和异构 Agent TCK；
- 验证单组件替换、多个有序扩展和整引擎替换。

门禁：新业务 Agent 不复制 Runtime、中控、HTTP 基座或状态转换代码即可运行。

### R5：手机银行案例提炼

- 将现有手机银行入口、领域 Agent、资产、评测、部署和前端迁入 example；
- 把可复用能力上收，把机构专属内容隔离到行内扩展；
- 展示多种意图扩展方式，不把 Skill 固化为唯一范式；
- 接入实验室安全 Provider 和上海知识 Provider 的模拟实现；
- 保留完整可运行、可观测、可评测链路。

门禁：删除 `examples/mobile-banking` 后，面客智能体基础制品仍可独立构建和测试。

### R6：社区合入

社区贡献拆为可独立评审的增量：

1. Finance API、扩展模型与 ADR；
2. Catalog 与 Runtime 单路径；
3. conversation、intent、execution、knowledge；
4. Starter、BOM、Testkit 和 TCK；
5. mobile-banking 参考案例；
6. 可被社区接受的通用 Connector。

每个增量都必须有文档、测试、兼容说明和最小样例，不提交包含全部现有工程的单个大 PR。
社区模块按 openJiuwen 当前 Java 17 基线构建；迁移前必须审计当前 Java 21 语法和 API，并对齐
`com.openjiuwen` 坐标、依赖版本、License、Checkstyle 和社区 CI。

## 13. 验收标准

完成目标至少满足：

- 生产只有一条 Runtime-backed 入站执行链；
- Agent 和能力资产只有一个解析入口和一个逻辑真值；
- 对话、意图、执行、知识四类引擎可以单独依赖和替换；
- 多路召回和仲裁作为普通意图组件可直接输出意图，真正 Slow Path 使用大模型 Agent Loop；
- 意图 Slow Path 和执行 Agent Loop 使用不同工具白名单、状态空间和结果契约；
- Skill 是意图和执行扩展形式之一，不是平台唯一编程模型；
- 安全、确认、幂等和审计不能被业务扩展绕过；
- 行内中间件通过 Connector 接入，不污染社区 API；
- 手机银行只是 `examples/mobile-banking`，基础模块不依赖案例；
- 基础发布面控制在 BOM、API、四类引擎、Starter 和 Testkit；
- 第三方业务 Agent 通过 TCK，不依赖社区实现内部类型；
- 当前已实现的关键行为有迁移回归证据，不以目录移动代替行为验证。

## 14. 非目标

- 不把客户知识平台整体合入 openJiuwen；
- 不把行内网关、认证、安全模型或中间件实现开源；
- 不要求所有意图能力都改写为 Skill；
- 不合并所有任务相关模型；
- 不要求所有领域 Agent 使用同一部署单元；
- 不以兼容当前 53 个 Reactor 模块为目标；
- 不在重构第一步进行一次性目录搬迁或包名替换。
