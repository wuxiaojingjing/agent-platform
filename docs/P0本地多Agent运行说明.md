# P0 本地多 Agent 运行说明

## 运行形态

本地完整环境包含手机银行助手、独立 A2A Gateway、26 个领域 Agent，以及
PostgreSQL、Redis、OpenSearch、Nacos、Jaeger、Prometheus、Grafana、Loki 和 Alloy。
领域 Agent 均为独立进程：
11 个 Host 通过 `LOADER_PATH` 加载领域扩展 JAR，15 个 Host 运行 Scaffold 节点。

跨 Agent 调用只能经过 `A2A Client -> Gateway -> Nacos -> A2A Server`。进程内 A2A
实现仅保留在测试工具中。

一次入口请求的运行边界如下：

```text
手机银行助手（主 Agent）
  -> 意图、上下文、记忆、缓存、任务中控
  -> A2A Client
  -> 独立 A2A Gateway
  -> Nacos 发现
  -> 领域 Host（子 Agent）
  -> 领域实现或 Scaffold
```

主 Agent 只拥有自己的上下文、记忆、任务状态和缓存。子 Agent 也使用同构 Runtime，
拥有按 `tenantId + agentId` 隔离的状态；A2A 信封只传递本次委托所需事实，不共享进程内对象。

## 启停和验收

```bash
./scripts/run-local.sh full
./scripts/smoke-p0.sh
./scripts/run-local.sh status
./scripts/run-local.sh down
```

`full` 启动完整观测栈；资源受限时可使用 `./scripts/run-local.sh core`，它保留 Jaeger，
但不启动 Prometheus、Grafana、Loki 和 Alloy。跳过故障注入或三支柱验收时，可分别给
`smoke-p0.sh` 传入 `--no-faults` 或 `--no-observability`。

本地 Compose 将整个 `dev/local/.dist` 目录只读挂载到 `/opt/dist`，Extension JAR 目录
挂载到 `/opt/extension`。不要恢复成把单个运行中 JAR 挂到 `/opt/app/app.jar`：Docker
Desktop 在宿主替换该文件时可能让旧 JVM 读到另一份归档，表现为随机
`NoClassDefFoundError`。`build-local-dist.sh` 在同一目录用临时文件加原子 `mv` 发布，目录
挂载使运行进程继续持有旧 inode，新容器启动时才读取新制品。

修改 Java 代码后的本地更新顺序固定为：先运行 `build-local-dist.sh`，再使用 Compose
重建需要升级的服务。构建本身不得导致现有容器退出；若日志出现类加载或 ZIP 错误，应停止
继续重建并改用不可变的 `.dist/releases/<build-id>` 目录，不得退回单文件挂载。

若 Docker Hub 网络不可用，可在被 Git 忽略的 `scripts/env.local.sh` 中指定组织批准的镜像源：

```bash
export NACOS_IMAGE=registry.example.com/nacos/nacos-server:v2.5.1
export PROMETHEUS_IMAGE=registry.example.com/prom/prometheus:v3.5.0
export LOKI_IMAGE=registry.example.com/grafana/loki:3.5.3
export ALLOY_IMAGE=registry.example.com/grafana/alloy:v1.10.1
export GRAFANA_IMAGE=registry.example.com/grafana/grafana:12.1.0
```

不要把企业镜像地址、认证信息或模型密钥写进 Compose。未配置覆盖项时，Compose 继续使用
对应的官方镜像版本。

控制台地址为 `http://localhost:8080/console/`，Gateway 健康检查为
`http://localhost:8086/actuator/health`，Nacos 控制台为
`http://localhost:8848/nacos/`。观测入口为：

| 入口 | 地址 | 用途 |
| --- | --- | --- |
| Grafana | `http://localhost:3000/` | 统一看板与日志、Trace 跳转 |
| Jaeger | `http://localhost:16686/` | 跨进程 Trace |
| Prometheus | `http://localhost:9090/` | 28 个应用指标目标 |
| Loki | `http://localhost:3100/ready` | 本地结构化日志查询 |

完整环境的固定验收口径是：配置 27、在线 27、领域 Agent 26、领域真实实现 11、
Scaffold 15、不健康 0、离线 0。手机银行助手是第 12 个已实现 Agent，也是第 27 个入口节点。

开发环境把 PostgreSQL `5432`、Redis `6379` 和 OpenSearch `9200` 仅绑定到 `127.0.0.1`，
用于 Maven Live Test；它们不会监听局域网地址。生产部署不得照搬这些端口映射。

## 状态语义

控制台使用两个互不覆盖的维度：实现状态为 `IMPLEMENTED` 或 `SCAFFOLD`，运行状态为
`ONLINE`、`UNHEALTHY` 或 `OFFLINE`。未实现但已经正常启动的占位进程应显示为
`SCAFFOLD/ONLINE`，并对委托明确返回 `DOMAIN_NOT_OPEN`。

上下文、缓存、任务和幂等键继续按 `tenantId + agentId` 隔离。所有进程可以共享本地
PostgreSQL 和 Redis，但不得省略逻辑身份边界。

## 请求级观测

控制台“运营观测 -> 最近请求”按 `session` 折叠，再按时间展开每一轮。它是容量受限的
内存环形缓冲，重启即空，不是审计流水。展开单轮后可同时查看：

- 主 Agent、A2A Gateway、子 Agent 及委托结果；
- 全局请求上下文的编译结果；
- 会话记忆和工作记忆的读取、写入与失效；
- 决策缓存的命中、未命中和写入；
- 意图、任务编排、A2A、响应渲染等模块的执行顺序、耗时和结果；
- 每个模块的脱敏输入、脱敏输出和同一 `traceId`。

输入输出摘要不得包含密钥、完整用户标识、账户号、槽位值或未经处理的完整用户输入。
`taskId`、`delegationId` 只作为 Trace/日志字段，不作为指标标签。控制台数据用于开发和故障
定位；合规审计应接入独立、持久化且有访问控制的审计系统。

## 三支柱关联

本地环境对所有应用采用 100% Trace 采样。HTTP 使用 W3C `traceparent` 传播，A2A 信封中的
业务 `traceId` 必须与当前 Trace 一致。一条跨 Agent 请求应在 Jaeger 中包含入口、Gateway
和目标 Host；Prometheus 应有 A2A 客户端、Gateway、服务端的调用量与分段耗时；Loki 应能
使用同一个 `traceId` 找到三个进程的结构化日志。

Grafana 预置以下看板：

- `Agent Platform Overview`：Agent 在线状态、A2A 结果和关联日志；
- `Agent Platform - A2A Operations`：调用量、成功率、P95 和失败原因；
- `Agent Platform - Intent, Context and Model`：意图、上下文、语义和模型降级；
- `Agent Platform - Runtime Health`：应用目标、CPU、JVM、线程和启动时间。

Prometheus 保留 7 天指标，Loki 保留 3 天日志。Alloy 仅通过只读 Docker Socket 采集
Compose 项目 `local` 的容器日志。

## P0 自动验收

`scripts/smoke-p0.sh` 校验 27/27 Agent 在线、41 条语义资产、11 个真实领域能力、15 个
Scaffold、语义召回和 A2A 来源，并通过故障注入校验 Gateway 不可达、领域 Agent 离线和
不健康实例过滤。故障退出时 `trap` 会恢复被停止的容器。

观测验收同时要求 Prometheus 的 28 个应用 Target 全部 `UP`、Jaeger 三服务 Trace 完整、
Loki 能按同一 `traceId` 关联三个进程，以及 Grafana 的三个数据源真实连通、四个看板均已
预置。完整构建使用 JDK 21 执行 `mvn clean verify`；模型和 Nacos Live Test 还需要本地
环境变量与正在运行的 Compose 基础设施。

2026-08-02 完整复跑结果为 `39/39`，未使用 `--no-faults` 或 `--no-observability` 跳过项。

强制非降级态验收使用当前 Reactor 构建上游依赖，并同时把开关作为环境变量和 JVM 属性传入：

```bash
source scripts/env.local.sh
export HUAWEI_FINANCE_AGENT_REQUIRE_LIVE_MODEL=true
mvn -pl agents/mobile-banking-assistant/backend -am \
  -Dtest=NoDegradationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DHUAWEI_FINANCE_AGENT_REQUIRE_LIVE_MODEL=true test
```

验收报告必须是 `Tests run: 2`、`Failures: 0`、`Errors: 0`、`Skipped: 0`。整类被条件过滤后
显示的 `Tests run: 0` 不属于通过。
