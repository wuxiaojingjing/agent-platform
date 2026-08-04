# Agent Platform

面向金融场景的可复用 Java Agent 平台。本仓库是完整的 Maven Reactor：平台框架、基础设施、领域 Agent、本地联调环境与文档均在此目录内。

手机银行助手（`agents/mobile-banking-assistant`）是入口产品 Agent；其余领域 Agent 与它同构，各自拥有意图、上下文、任务中控、记忆与跨 Agent 委托能力。

## 运行形态

```text
前端 → mobile-banking-assistant
     → Agent Runtime（意图 / 上下文 / 中控 / 护栏 / 幂等）
     → A2A Client → Gateway → Nacos → 目标 Agent Host
     → 目标 Agent Runtime → 本地业务 Port 或继续经 Gateway 委托
```

- A2A 协议：`a2a/2`，HTTP 路径 `/a2a/v2/*`（不兼容 v1）
- 目标 Agent 的 GOAL / TASK 均经目标 Runtime；生产 Host 不装配领域直通节点
- 跨 Agent 主体只传播不透明引用；日志、指标与 Span 不记录引用值
- Slow Path 默认 `CONFIRM_EACH`；本地产品与 Extension 使用 `AUTO_READ_ONLY`
- Extension 经类型化业务 Port 调后端；本地由 `banking-systems-simulator` 提供确定性契约
- Scaffold Agent 委托固定返回 `DOMAIN_NOT_OPEN`，不制造假数据

## 仓库结构

```text
framework/           平台内核
  bom/               依赖版本 BOM
  contracts/         agent-api / task-api / a2a-api / stability-api
  intent-engine/     api / fastpath / slowpath / default / starter
  runtime/           context / task-orchestrator / response / agent-runtime-*
  registry/          asset-registry / capability-registry
  host/              agent-host-app（一 Agent 一进程宿主）
  observability/     指标与观测
  testing/           TCK、Testkit、进程内 A2A 测试工具
  starters/          聚合 Starter

infrastructure/      可替换适配层
  a2a/               client / server / gateway（独立应用）
  persistence/       JDBC
  cache/             Redis
  discovery/         Nacos
  model/             OpenAI-compatible 模型网关
  search/            OpenSearch
  workflow/          openjiuwen-adapter
  observability/     观测导出

agents/              产品与领域 Agent（见下）
agent-template/      新建 Agent 的目录模板
samples/agents/      联调样例（含 banking-systems-simulator）
dev/local/           Docker Compose、观测配置、本地分发
scripts/             构建、启动、Smoke、密钥模板、预检
docs/                架构与接入文档
tools/               辅助工具（如 promptopt）
```

每个 `agents/<agent>/` 统一布局：

```text
agent.yaml
assets/
backend/        # Extension 才有
frontend/       # 产品需要时才有（如手机银行助手）
eval/
deploy/
README.md
```

### 当前 Agent

| 类型 | Agent |
| --- | --- |
| 入口产品 | `mobile-banking-assistant` |
| Extension（可执行） | `account` `transfer` `creditcard` `wealth-aggregate` `fund-service` `insurance-service` `finance-assistant` `deposit-service` `loan-service` `payroll-service` `wealth-product` |
| Scaffold（占位） | `advisory-service` `benefits-ops` `bond-service` `branch-service` `channel-settings` `e-cny` `enterprise-service` `fx-service` `life-service` `livelihood-service` `payment` `personal-info` `precious-metal` `security-service` `vip-service` |

新建 Agent：

```bash
./scripts/new-agent.sh <dir-name> <agent.id> <显示名>
```

完整接入步骤见 [开发者自建子 Agent 接入指南](docs/开发者自建子Agent接入指南_v0.1.md)。

## 环境要求

- JDK 21
- Maven 3.9+
- Docker Desktop（本地多 Agent 联调）
- Node.js（仅手机银行助手前端）

## 快速开始

```bash
# 1. 环境与密钥
source scripts/env.sh
cp scripts/env.local.sh.example scripts/env.local.sh
# 编辑 scripts/env.local.sh，填入模型 API Key

# 手机银行本地密钥（可选，与 env.local.sh 二选一或叠加）
cp agents/mobile-banking-assistant/deploy/application-local.yml.example \
   agents/mobile-banking-assistant/application-local.yml

# 2. 构建
mvn clean verify

# 3. 前端（控制台）
npm --prefix agents/mobile-banking-assistant/frontend ci
npm --prefix agents/mobile-banking-assistant/frontend run build

# 4. 本地全量启动
./scripts/run-local.sh full

# 5. Smoke
./scripts/smoke-p0.sh
```

本地密钥文件（`scripts/env.local.sh`、`**/application-local.yml`）已被 `.gitignore` 忽略，**不要提交**。

### 本地入口

| 服务 | 地址 |
| --- | --- |
| 控制台 | http://localhost:8080/console/ |
| A2A Gateway | http://localhost:8086 |
| Jaeger | http://localhost:16686 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

常用脚本：

| 脚本 | 作用 |
| --- | --- |
| `scripts/run-local.sh full` | 构建分发并启动完整 Compose |
| `scripts/run-local.sh core` | 仅核心基础设施 + 全部 Agent Host |
| `scripts/smoke-p0.sh` | P0 冒烟 |
| `scripts/smoke-p1.sh` | P1 冒烟 |
| `scripts/git-preflight.sh` | 提交前秘密扫描（不改动工作区） |

## 扩展边界

业务 Agent **不依赖**其他 Agent 的实现代码。协作只能经 `CapabilityDelegator → A2A Gateway`。

Extension 的 `backend` 只应实现：

- 领域业务 Port 与类型化 DTO
- HTTP / 消息等向下适配器
- `TechDomainAgent` 的参数校验与 `TaskResult` 映射
- 必要的护栏、路由或平台 SPI 覆盖

平台 SPI 使用 Java 接口、Spring 条件装配与 Bean 覆盖；可复用默认组件，也可用同类型 Bean 覆盖局部流程。

## 状态与所有权

- 上下文、任务、计划、记忆和缓存属于具体 Agent
- 数据按 `tenantId + agentId` 隔离；共享 PostgreSQL / Redis 不改变所有权
- A2A 目标会话由 `sourceAgentId + sourceSessionRef + rootTaskId` 派生并做不可逆摘要
- `agentId + invocationOrigin + sourceInvocationId` 唯一；同一 `delegationId` 重放返回首次结果
- 任务中控是唯一状态事务边界；业务 Port 不改任务状态

## 文档

- [总体架构 v0.7](docs/Agent平台总体架构草案_v0.7.md)
- [P0 本地多 Agent 运行](docs/P0本地多Agent运行说明.md)
- [P1 多层协同与 Slow Path 验收](docs/P1多层协同与SlowPath验收.md)
- [Slow Path 规则锚定与 Planner 候选治理](docs/SlowPath规则锚定与Planner候选治理方案.md)
- [开发者自建子 Agent 接入指南](docs/开发者自建子Agent接入指南_v0.1.md)
- [入口意图路由与运营干预](docs/入口意图路由实现与运营干预设计_v0.1.md)
- [Agent Loop 框架与模块输入输出](docs/AgentLoop框架与模块输入输出设计_v0.1.md)

更多 ADR 与演进稿见 [`docs/`](docs/)。
