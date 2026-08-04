# Slow Path 规则锚定与 Planner 候选治理方案

## 1. 目标

Slow Path 的快慢只描述意图识别方式，不描述任务在哪里执行。意图计划确定后，每一步仍可由本
Agent 本地执行，也可通过 A2A Gateway 委托子 Agent。

本方案解决的问题是：规则已经正确拆出“查余额，再查基金”两个步骤后，Planner 曾经可以用资产
列表前几张卡整体覆盖规则计划，把它改成两个导航能力。治理后的固定顺序是：

```text
规则切分步骤边界并召回每步候选
  -> 高置信步骤 LOCKED
  -> 弱证据步骤 PREFERRED
  -> 未识别步骤 UNRESOLVED
  -> Planner 只在每步允许候选内消歧或补全
  -> Runtime 统一 Grounding
  -> 任一项不合格则原样使用 RULE 计划
```

规则不负责生成执行许可，Planner 不负责改变用户的步骤边界。最终执行仍经过上下文、中控、护栏、
任务状态与幂等控制。

## 2. 为什么不是“规则优先即规则锁死”

把规则放在前面有三类风险：词表覆盖不足会漏识别，短关键词会产生冲突，历史规则可能压住新的业务
表达。因此规则结果按证据强度分级，而不是无条件锁定：

| 分级 | 判定 | Planner 权限 |
| --- | --- | --- |
| `LOCKED` | Top1 >= 0.55 且分差 >= 0.12 | 不得替换 |
| `PREFERRED` | Top1 > 0.20，但未达到锁定线 | 仅可在本步骤候选中消歧 |
| `UNRESOLVED` | 未达到最低识别线 | 有候选时可补全，无候选时停止并澄清 |

与能力卡 `utterances` 完全一致的表达得分为 1.0。重复 utterance 由资产 Lint 阻断，避免两个能力都
声称同一条强证据。

这种设计保留了规则的确定性和可解释性，但没有让规则获得超出证据的权限。模型也不是自由规划器，
而是受逐步骤候选约束的消歧器和补全器。

## 3. 数据契约

`SubIntent.resolution` 使用 `PlanResolution`：

```text
strength       LOCKED | PREFERRED | UNRESOLVED
topScore       本步骤规则 Top1 分数
margin         Top1 与 Top2 分差
candidateIds   Planner 对本步骤可选的 capabilityId
evidenceRefs   规则证据引用
```

候选按“分数降序、capabilityId 升序”稳定排序，每步最多 3 个。当前仍处于开发阶段，
`resolution` 是必填契约：源码调用、持久化 JSON 和测试数据必须一次性同步升级，缺少该字段的旧计划
直接判为不可恢复，不保留默认补值或双轨读取逻辑。

计划来源含义：

- `RULE`：未调用模型，或模型结果被拒绝；
- `HYBRID`：规则已识别至少一个步骤，Planner 在约束内完成消歧或补全；
- `PLANNER`：原计划所有步骤均未识别，最终全部由 Planner 在候选内补全。

## 4. 候选治理

`fusion.yaml` 的规划配置为：

```yaml
planning:
  preferredMin: 0.20
  maxCandidatesPerStep: 3
  navigationMarkers: [打开, 进入, 跳转, 菜单, 页面, 去]
```

导航能力 `cap.nav.*` 只有在当前子句包含导航标记时才可进入候选。例如“查询基金产品A”只保留基金
查询能力，“打开基金选品”才允许基金选品导航卡。

候选并集使用逐步骤轮询：先为每步取第一个候选，再取每步第二个，直到达到
`slowpath.maxCandidates`（默认 8）。若预算内无法覆盖每一个步骤，Runtime 不调用 Planner，避免某
一步在没有工具约束的情况下被模型猜测。

Planner 输入显式携带每步原始子句、允许候选、分级与条件。系统提示要求一条输入步骤对应一条
`propose`，不得增删、合并或重排。

## 5. Grounding 策略

所有 `IntentPlanner` 实现，包括业务覆盖 Bean，都必须经过 Runtime 的 `PlanGroundingPolicy`。
验证项按顺序包括：

1. 输出步骤数与规则步骤数一致；
2. 原始子句、order、relation、condition 不变；
3. `LOCKED` capabilityId 不变；
4. 每步选择属于该步骤 `candidateIds`；
5. capabilityId 不重复，避免按能力命名空间聚合事实时覆盖。

规划结果使用低基数结果码：`ALL_LOCKED`、`ACCEPTED`、`EMPTY_CANDIDATES`、
`STEP_COUNT_MISMATCH`、`LOCKED_REPLACED`、`OUT_OF_CANDIDATES`、`CONDITION_LOST`、
`DUPLICATE_CAPABILITY`、`PLANNER_FALLBACK`。

任意一项失败都返回原始 `RULE` 计划，不局部拼接一个未经验证的模型结果。模型理由不写入计划、
日志或面客回复。

## 6. 示例

“查一下余额，然后查询基金产品A”：

```text
1. cap.account.balance.query  LOCKED  candidates=[cap.account.balance.query]
2. cap.fund.product.query     LOCKED  candidates=[cap.fund.product.query, ...]
```

两步均锁定，直接使用 RULE 计划，不调用 Planner。即使资产中存在
`cap.nav.fund_service_基金选品`，因为原句没有导航标记，它也不会进入第二步候选。

若第二步只有弱关键词证据，则为 `PREFERRED`。Planner 可以在第二步候选中选择基金查询，但不能把
第一步余额替换成“查询我的资产”，也不能交换两步顺序。若某一步完全没有候选，计划保留并进入现有
澄清/停止流程。

## 7. 观测与验收

计划蓝图展示每步强度、Top1、分差、候选和证据。Trace 与指标只记录锁定、待消歧、未识别步骤数
及 Grounding 结果，不使用 taskId、planId、用户输入或槽位值作为指标标签。

固定验收表达为“查一下余额，然后查询基金产品A”：

- 默认 Planner 开启，但因两步 `ALL_LOCKED` 不发生模型调用；
- 持久化能力顺序固定为余额后接基金；
- 计划中不得出现 `cap.nav.*`；
- 首轮执行余额并使用 `tpl.plan.progress`；
- 重启后“继续”执行基金并使用 `tpl.plan.result`；
- 步骤表、游标、幂等和 A2A 执行语义保持不变。

本方案不允许 Planner 动态新增、删除或拆分步骤。需要动态任务分解时，应使用独立版本和新的执行
安全边界，而不是放宽本策略。
