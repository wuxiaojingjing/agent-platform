# Huawei Finance Agent Platform

面向金融 Agent 开发者的可复用 Java 平台。手机银行助手只是一个产品 Agent；26 个领域 Agent
与它同构，均可拥有自己的意图、上下文、中控、任务状态、记忆、缓存和下级委托。

面向 openJiuwen 的目标形态是 `agent-solution/finance/customer-facing-agent` 下的“金融面客智能体
解决方案”；手机银行将作为 `examples/mobile-banking` 参考案例，而不是基础框架本身。目标架构、
组织分工、发布制品和分阶段重构计划见
[openJiuwen 金融面客智能体解决方案总体设计与重构计划 v0.1](docs/openJiuwen金融面客智能体解决方案总体设计与重构计划_v0.1.md)。
渐进式重构代码位于工作区同级的 `../openjiuwen-finance-customer-facing-agent/`；本 Reactor 不再
包含该目录，现有平台代码继续作为行为基线和迁移连接器的实现来源。

## 当前运行形态

```text
前端 -> mobile-banking-assistant
     -> 本 Agent Runtime（意图 / 上下文 / 中控 / 护栏 / 幂等）
     -> A2A Client -> 独立 Gateway -> Nacos -> 目标 Agent Host
     -> 目标 Agent Runtime -> 本地业务 Port 或继续经 Gateway 委托
```

- A2A 协议为 `a2a/2`，HTTP 路径为 `/a2a/v2/*`，不兼容 v1。
- 目标 Agent 的 GOAL/TASK 都经过目标 Runtime；生产 Host 不装配领域直通节点。
- 跨 Agent 主体只传播不透明引用，日志、指标和 Span 不记录引用值。
- Slow Path 默认 `CONFIRM_EACH`；本地产品与 11 个 Extension 使用 `AUTO_READ_ONLY`。
- 11 个 Extension 通过类型化业务 Port 调后端；本地由独立 simulator 提供确定性契约。
- 15 个 Scaffold 只返回 `DOMAIN_NOT_OPEN`，不制造假数据。

## 目录

```text
framework/
  contracts/              agent-api / task-api / a2a-api / stability-api
  intent-engine/          api / fastpath / slowpath / default / starter
  runtime/                context / task-orchestrator / response / agent-runtime-*
  registry/               asset-registry / capability-registry
  host/agent-host-app     通用一 Agent 一进程宿主
  testing/                TCK、Testkit、进程内 A2A 测试工具
infrastructure/
  a2a/                    client / server / gateway（独立应用）
  persistence/ cache/ search/ discovery/ model/ workflow/ observability/
agents/
  mobile-banking-assistant/
  account/ transfer/ creditcard/ wealth-aggregate/
  fund-service/ insurance-service/ finance-assistant/
  ...15 个 Scaffold
samples/agents/
  banking-systems-simulator/   本地契约后端，不是 Agent
dev/local/                Compose、观测配置和本地分发产物
scripts/                  构建、启动、Smoke、Git 预检
docs/                     架构与运行文档
```

每个 `agents/<agent>/` 统一使用：

```text
agent.yaml
assets/
backend/        # 有 Java 扩展时才存在
frontend/       # 产品需要时才存在
eval/
deploy/
README.md
```

## 构建与运行

要求 JDK 21、Docker Desktop、Node.js。

```bash
source scripts/env.sh
mvn clean verify
npm --prefix agents/mobile-banking-assistant/frontend ci
npm --prefix agents/mobile-banking-assistant/frontend run build
./scripts/run-local.sh full
./scripts/smoke-p0.sh
```

本地入口：

- 控制台：`http://localhost:8080/console/`
- A2A Gateway：`http://localhost:8086`
- Jaeger：`http://localhost:16686`
- Grafana：`http://localhost:3000`
- Prometheus：`http://localhost:9090`

本地密钥只放在被 Git 忽略的 `scripts/env.local.sh`，不得写入 Compose、日志或测试报告。

## 扩展一个 Agent

完整的同构 Java、异构 HTTP、上下文传递和 TCK 步骤见
[开发者自建子 Agent 接入指南](docs/开发者自建子Agent接入指南_v0.1.md)。

只配置 `agent.yaml` 和资产的 Agent 当前以 Scaffold 运行，用于占位但不执行能力。可执行的 Java Agent
使用 Extension 模式；需要代码时，`backend` 只实现：

- 领域业务 Port 与类型化 DTO；
- HTTP/消息等向下适配器；
- `TechDomainAgent` 的参数校验和 `TaskResult` 映射；
- 必要的护栏、路由或平台 SPI 覆盖。

业务 Agent 不依赖其他 Agent 的实现。协作只能通过 `CapabilityDelegator -> A2A Gateway`。
平台 SPI 使用 Java 接口、Spring 条件装配和 Bean 覆盖；业务方可以复用默认组件，也可以提供同类型
Bean 覆盖局部流程，而不复制整个 Runtime。

## 状态与所有权

- 上下文、任务、计划、记忆和缓存属于具体 Agent。
- 数据按 `tenantId + agentId` 隔离；共享 PostgreSQL/Redis 不改变所有权。
- A2A 目标会话由 `sourceAgentId + sourceSessionRef + rootTaskId` 派生并做不可逆摘要。
- `agentId + invocationOrigin + sourceInvocationId` 唯一；同一 `delegationId` 重放返回首次任务结果。
- 任务中控是唯一状态事务边界，业务 Port 不改任务状态。

## 文档

- [openJiuwen 金融面客智能体解决方案总体设计与重构计划 v0.1](docs/openJiuwen金融面客智能体解决方案总体设计与重构计划_v0.1.md)
- [总体架构 v0.7](docs/Agent平台总体架构草案_v0.7.md)
- [总体架构 v0.6](docs/Agent平台总体架构草案_v0.6.md)
- [P0 本地多 Agent 运行](docs/P0本地多Agent运行说明.md)
- [Slow Path 规则锚定与 Planner 候选治理](docs/SlowPath规则锚定与Planner候选治理方案.md)
- [P1 多层协同与 Slow Path 验收](docs/P1多层协同与SlowPath验收.md)

发布前执行 `./scripts/git-preflight.sh`。它只报告迁移分组与秘密阻断状态，不会执行
`git add`、提交、删除或轮换本地密钥。
