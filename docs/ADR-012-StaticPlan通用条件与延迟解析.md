# ADR-012：Static Plan 通用条件与延迟解析

> 状态：已实现  
> 日期：2026-08-02  
> 关联：`ADR-010-入口路由Decision与任务形态判定.md`、`AgentLoop框架与模块输入输出设计_v0.1.md`

## 1. 决策

`STATIC_PLAN` 在执行前固定步骤、能力和依赖关系，运行期不得追加或重新选择能力。条件只控制一个
已知步骤是否执行，不改变计划的能力集合。

框架不得硬编码余额、工资、转账等业务语义。条件使用通用、类型化表达式；入口无法可靠提取表达式
时，保留用户的自然语言条件并标记为 `DEFERRED`，待依赖步骤产生真实结构化结果后再解析。

后置模型只负责把自然语言编译为受控表达式，不能直接决定 `PROCEED`、`STOP`，也不能选择或追加
能力。最终条件真假必须由确定性求值器计算。

## 2. 计划契约

`SubIntent` 需要显式携带稳定步骤标识、依赖和条件：

```java
public record SubIntent(
        int order,
        String text,
        String capabilityId,
        String summary,
        IntentRelation relation,
        String condition,
        PlanResolution resolution,
        String stepId,
        List<String> dependsOn,
        PlanCondition planCondition) {
}

public record PlanCondition(
        String originalText,
        ConditionExpression expression,
        ResolutionState state) {

    public enum ResolutionState {
        STRUCTURED,
        DEFERRED
    }
}
```

`condition` 是保留兼容的条件原文，`planCondition.originalText` 是新的审计原文；
`planCondition.expression` 是唯一可执行条件。

## 3. 通用表达式

表达式只提供通用运算符和受控数据引用：

```text
Operator = EQ | NE | GT | GTE | LT | LTE | AND | OR | NOT | EXISTS
Source   = STEP_OUTPUT | PARAMETER | LITERAL | EXPRESSION
```

步骤输出通过 `stepId + JSON Pointer` 引用，计划参数通过参数名引用。框架不包含业务字段白名单或
业务规则 ID。

示例：

```json
{
  "operator": "GTE",
  "operands": [
    {"source": "STEP_OUTPUT", "stepId": "goal-1", "pointer": "/availableBalance"},
    {"source": "PARAMETER", "parameter": "amount"}
  ]
}
```

该示例中的字段语义来自能力资产，不进入框架代码。

## 4. 校验边界

计划落库或后置解析后，统一由 `PlanConditionValidator` 校验：

1. `stepId` 必须存在，且属于当前步骤的 `dependsOn`。
2. JSON Pointer 必须存在于依赖能力的 `CapabilityCard.outputSchema`。
3. 参数必须存在于当前能力的 `CapabilityCard.inputSchema`。
4. 比较、逻辑运算两端必须满足 JSON Schema 类型约束。
5. 禁止脚本、方法调用、反射、SpEL、SQL 或任意代码执行。
6. 条件不得引用未完成步骤，不得产生候选能力或修改计划拓扑。

Schema 缺失、引用非法或类型不明确时，不允许自动执行。

## 5. 延迟解析

入口能够产生并通过校验的表达式记为 `STRUCTURED`。入口只能提取自然语言条件时仍可建立
`STATIC_PLAN`，但条件记为 `DEFERRED`。

执行流程：

```text
执行依赖步骤
  -> 按 stepId 持久化结构化结果
  -> 条件为 STRUCTURED：直接确定性求值
  -> 条件为 DEFERRED：ConditionResolver 后置编译
  -> 校验编译结果
       -> 合法：确定性求值
       -> 非法、模型不可用或仍无法解析：WAITING_USER
```

`ConditionResolver` 的输入只包括条件原文、依赖步骤及其实际事实和 `outputSchema`、当前参数及
`inputSchema`。其输出只能是 `ConditionExpression` 或 `UNRESOLVED`。

## 6. 求值与事实

步骤结果必须按 `stepId` 隔离保存，不再按 `capabilityId` 合并，也不得把多步结果扁平化后按字段名
猜测来源。求值器通过表达式中的精确引用读取事实。

求值结果只有：

- `PROCEED`：条件成立，进入当前能力的正常任务链；
- `STOP`：条件不成立，当前步骤记录为 `CONDITION_NOT_MET`；
- `UNDECIDED`：条件无法安全判断，计划进入 `WAITING_USER`。

参数和值不得在条件层进行业务化或宽松转换。类型归一化应在能力输入和结果契约边界完成。

## 7. 用户澄清

后置解析仍失败时使用条件放行澄清：

```text
已完成「{前序步骤}」，但暂时无法自动判断是否满足您设置的条件
“{条件原文}”。是否继续办理「{当前步骤}」？
```

操作为“继续办理”和“不办理”。“继续办理”只表示条件放行，不构成业务执行确认；R1/R2 能力仍
必须经过各自的 Review 或 Confirm。“不办理”将当前步骤记为 `CONDITION_NOT_MET`。

## 8. 持久化与审计

原始 `IntentPlan` 保持为不可变计划蓝图。后置解析结果单独持久化，至少记录：

```text
planId, stepIndex, sourceText, compiledExpression, resolutionOutcome,
factDigest, modelVersion, promptVersion, createdAt
```

`factDigest` 用于证明表达式解析对应哪一份依赖事实，并避免恢复时重复调用模型。

## 9. 降级规则

- 无条件固定计划可以完全由规则生成和执行。
- 条件提取失败不会丢失条件，也不会把它改写成业务硬编码。
- 后置模型失败不会导致计划改选能力或升级为 Agent Loop。
- 无法得到合法表达式时必须询问用户，不得直接消费自然语言字符串控制执行。
- 只有执行结果决定“选择哪个新能力”时，才进入 `START_LOOP`。

## 10. 实施结果

1. 已为步骤增加 `stepId`、`dependsOn` 和 `PlanCondition`，旧构造方式保留兼容。
2. 内部事实已按 `stepId` 存取；对外响应仍保留按 `capabilityId` 聚合的兼容视图。
3. 已实现 `ConditionExpression`、`PlanConditionValidator` 和通用确定性求值器。
4. 入口模型契约支持可选结构化表达式；无法可靠绑定时保留为 `DEFERRED`。
5. `DeepAgentConditionResolver` 只拥有表达式提案工具；提案经 Schema 校验后单独持久化。
6. 非法或无法解析的条件进入 `WAITING_USER`；“继续办理”只放行条件，不能跳过风险确认。
7. 已覆盖 Schema 引用、类型冲突、延迟编译真假、模型不可用、恢复和风险确认单测。
8. 完整 DAG 调度不在本次范围内，当前仍按持久化游标推进固定计划。

环境验证说明：本地单元与应用装配测试已通过；依赖 Redis/PostgreSQL 的真实四出口端到端和 JDBC
往返测试在中间件不可用时显式跳过，发布门禁仍需在完整环境中执行。
