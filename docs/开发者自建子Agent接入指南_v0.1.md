# 开发者自建子 Agent 接入指南 v0.1

## 1. 先明确“子 Agent”是什么

子 Agent 是一次委托关系中的目标，不是特殊 Java 子类。任何可发现的 Agent 都拥有自己的身份、上下文、
任务状态、护栏和存储；当父 Agent 通过 A2A 把 TASK 或 GOAL 委托给它时，它在这条调用链中成为子 Agent。
同一个 Agent 在另一条调用链里可以是入口或父 Agent，并继续委托下一级。

开发者不得把子 Agent 实现成父 Agent 进程里的普通工具类，也不得让父子 Agent 直接读写同一份任务表。
进程内调用只用于测试；生产调用固定经过 A2A Gateway。

## 2. 选择接入形态

| 形态 | 当前支持度 | 适用场景 | 接入边界 |
| --- | --- | --- | --- |
| Java Extension Agent | 完整支持 | 行内 Java 高码、需要复用平台 Runtime | `agent.yaml + assets + backend JAR` |
| 独立 A2A Agent | HTTP 基础契约已支持 | Python、低码、其他框架或三方 Agent | `/a2a/v2/inbound` JSON 服务 |
| Scaffold | 完整支持但不可执行 | 先占身份、领域和资产目录 | 委托固定返回 `DOMAIN_NOT_OPEN` |

配置型可执行 Agent 仍是目标态；当前通用 Host 的无代码模式只有 `scaffold`，不能把它当成已经交付的能力。

## 3. Java Extension Agent

### 3.1 创建目录

```bash
cd agent-platform
./scripts/new-agent.sh card-insight agent.card_insight 卡片分析助手
```

脚本只创建安全的 Scaffold。随后把 `agent.yaml` 改为实际交付形态：

```yaml
agent:
  id: agent.card_insight
  displayName: 卡片分析助手
  roles: [domain]
  domains: [card_insight]
implementation:
  mode: extension
  artifact: "com.huawei.finance:card-insight"
runtime:
  intentEngine: default
  context: enabled
  taskOrchestrator: enabled
a2a:
  inbound: true
  delegation:
    enabled: true
assets:
  path: ./assets
storage:
  namespace: card-insight
```

`agent.id` 是路由和隔离键，必须全局唯一且不能从目录名推导。`storage.namespace` 不能与其他 Agent 共用。

### 3.2 声明能力

至少维护两类卡：

```text
assets/capabilities/agents/card_insight.yaml  # Agent 父卡，供发现
assets/capabilities/card_insight.yaml         # TOOL/WORKFLOW 能力卡
```

父卡声明 `capabilityId=agent.card_insight`、领域、版本、状态和 timeout。叶子能力声明父卡、输入 Schema、
必填槽位、风险、sideEffects、幂等策略、正向话术和负向边界。AgentCard 从资产投影生成，不再手写第二份。

风险等级不是展示字段：R1/R2 必须由目标 Agent 本地护栏重新判定，上游确认只能作为事实证据，不能让
目标 Agent 跳过确认。

### 3.3 实现领域边界

在 `backend/` 创建 Maven 模块并实现：

- 类型化业务 Port 和 DTO；
- HTTP/消息适配器；
- `TechDomainAgent`；
- Spring Boot `@AutoConfiguration`；
- 必要的域内指代解析或平台 SPI。

`TechDomainAgent` 最低实现：

```java
public final class CardInsightAgent implements TechDomainAgent {
    private final CardInsightPort port;
    public CardInsightAgent(CardInsightPort port) { this.port = port; }
    public String techDomainCode() { return "card_insight"; }
    public String agentId() { return "agent.card_insight"; }
    public boolean supports(String capabilityId) {
        return "cap.card.insight.query".equals(capabilityId);
    }
    public Set<String> advertisedCapabilities() {
        return Set.of("cap.card.insight.query");
    }
    public TaskResult execute(UnifiedTask task) {
        // 校验参数后调用业务 Port；不得修改平台任务表或自行重试。
        return port.execute(task);
    }
}
```

实现必须遵守：无平台签发的幂等键不得执行；业务异常映射为结构化 `TaskResult`；不面客输出自然语言；
不自行修改任务状态；不直接依赖父 Agent 或其他 Agent 的实现模块。

扩展 JAR 需要加入根 Maven Reactor，自动配置类加入
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。运行时由通用 Host
读取 `AGENT_HOME/agent.yaml`，并通过 `LOADER_PATH` 加载扩展 JAR。

### 3.4 让它继续委托子 Agent

不要注入另一个 Agent 的实现 Bean。当前 Agent 的 Runtime 根据能力卡选择 `CapabilityDelegator`，经 Gateway
发出新的 A2A 委托。新信封必须沿用 `rootTaskId` 和 Trace，设置本层 `parentTaskId/sourceTaskId`，追加
`delegationPath`，创建新的 `delegationId`，并收缩绝对 deadline。

## 4. Python、低码或其他异构 Agent

异构 Agent 不依赖本仓库 Java 类，但必须实现同一线上协议：

```http
POST /a2a/v2/inbound
Content-Type: application/json
```

入站校验顺序：协议版本、目标 agentId、主体上下文、deadline、Trace、委托环路、delegationId 去重，
然后才创建本地任务并执行本地护栏。返回值必须符合 `DelegationReceipt`：

```json
{
  "version": "a2a/2",
  "delegationId": "...",
  "outcome": "SUCCEEDED",
  "facts": {"resultRef": "opaque-ref"},
  "missingSlots": [],
  "reasonCode": null,
  "diagnostics": null
}
```

`SUCCEEDED` 但 `facts` 为空会被 Gateway 判 FATAL。`NEED_USER` 必须返回结构化 `missingSlots`。异常不能穿透
成无响应；结果未知返回 PARTIAL，禁止自动重做有副作用操作。

服务通过 Nacos 或 Gateway 静态路由注册 `agentId -> baseUrl`，同时发布与能力资产一致的 AgentCard。
发现、执行和资产版本不一致时拒绝流量，不能让 Gateway 猜测兼容性。

当前 `a2a/2` Java 契约已经支持目标、参数和 confirmedFacts；完整 ContextEnvelope/ContextDelta、作用域和
版本协商仍是已知缺口。在该契约落地前，异构 Agent 首期只开放 R0 TASK。不得用自定义字段偷传完整聊天
历史，也不得把 diagnostics 当成事实。

## 5. 子 Agent 如何消费上下文

目标契约落地后，每个自建 Agent 都实现相同的四步：

1. 校验 `contextLeaseId`、`baseStateVersion`、有效期和读写作用域；
2. 将平台信封适配成本框架的本地 Context/Memory，不改变来源引用；
3. 只执行 `allowedCapabilities` 范围内的能力；
4. 以 ContextDelta 回传新事实、来源、时点、有效期、待澄清项和记忆写入建议。

子 Agent 可以保存自己的会话和任务状态，但不能把父 Agent 的信封当作自己的权威状态。收到重复
`delegationId` 时返回首次结果；收到同一目标的新补参轮次时使用新 delegationId，并通过派生目标会话恢复
本地状态。

对于模型或框架差异，Token 预算由目标 Agent 使用自己的 tokenizer 再校验。源 Agent 计算的 token 数只能
作为上限提示，不能证明目标模型一定装得下。

## 6. 最低测试门禁

Java Agent 必须继承并跑通 `AgentNodeContract`，同时为本地 `TechDomainAgent` 跑领域契约。异构 Agent
使用相同的黑盒 HTTP TCK。最低断言包括：

- 本域 TASK 返回版本和 delegationId 正确的强制信封；
- 外域 TASK 返回 NOT_MINE；
- 同 delegationId 重放不产生第二次副作用；
- SUCCEEDED 必须有结构化事实；
- NEED_USER 必须有结构化缺槽；
- GOAL 只投给自治 Agent；
- 主体引用和敏感字段不进入日志、指标、Span 或 diagnostics；
- 父子任务 Trace 连续、任务状态独立；
- 上下文用例断言 `usedContextRefs` 和事实来源，不能只断言最终话术。

推荐验证顺序：

```bash
mvn -pl agents/card-insight/backend -am test
mvn -pl framework/testing/agent-tck -am test
./scripts/run-local.sh full
./scripts/smoke-p0.sh
```

跨 Agent 上下文专项用例见 `docs/多轮上下文改写与跨Agent传递验收用例_v0.1.md`。

## 7. 发布检查单

- Agent ID、领域码、父卡、叶子卡和 Nacos serviceName 一致；
- AgentCard/能力卡 Schema 与实现参数一致；
- 每个共享存储键都包含 tenantId + agentId；
- 业务 Port 有连接/读取 timeout，错误分类稳定；
- R1/R2 的本地确认、幂等、补偿和审计完整；
- A2A deadline、环路、重放和 PARTIAL 行为通过；
- ContextDelta 不越权、不覆盖关键冲突、不携带完整历史；
- Jaeger 可看到父 Agent、Gateway、目标 Runtime、目标 context/task 以及下级委托。
