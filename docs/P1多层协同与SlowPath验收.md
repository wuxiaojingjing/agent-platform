# P1 多层协同与 Slow Path 验收

## 语义边界

- `FAST_PATH/SLOW_PATH` 只描述意图识别路径。
- `LOCAL/A2A` 只描述任务调用来源。
- `AGENT` 能力通过 A2A GOAL 交给目标 Agent 重新识别。
- `TOOL/SKILL` 能力通过 A2A TASK 执行已确定能力，目标侧仍经过上下文、中控、护栏和幂等。

## 固定场景

多层协同使用“请金融助手帮我查询基金产品A”和页面 `finance-center`：

```text
手机银行助手
  -> A2A Gateway -> 金融助手 GOAL
  -> A2A Gateway -> 基金助手 TASK
```

Slow Path 重启续办使用“查一下余额，然后查询基金产品A”。计划步骤结果保存在
`agent_intent_plan_step`，游标与成功步骤通过单条 SQL 原子提交；重启后的“继续”从持久化游标恢复。
本轮已确认参数保存在 `agent_intent_plan.parameters`，属于 Static Plan Runtime 的权威快照；平台任务
只保存 `runtimeType=STATIC_PLAN + runtimeRef=planId`，不复制参数、步骤或游标。
Planner 保持默认开启；两步规则结果必须为 `LOCKED`，所以直接使用 RULE 计划，不发生模型覆盖。
候选分级、导航过滤和 Grounding 回退口径见
[Slow Path 规则锚定与 Planner 候选治理](SlowPath规则锚定与Planner候选治理方案.md)。

## 验收命令

```bash
./scripts/build-local-dist.sh
./scripts/run-local.sh full
./scripts/smoke-p0.sh
./scripts/smoke-p1.sh
```

`smoke-p1.sh` 会临时重建手机银行助手并自动恢复默认配置，不停止数据库、Gateway 或领域 Agent。
它同时验证多跳血缘、Runtime 重放、主体引用不进入回执和日志，以及 Slow Path 跨进程恢复。

## 2026-08-01 验收结果

完整复跑结果为 `PASS=11 FAIL=0`。已验证：

- 手机银行助手 GOAL 到金融助手、金融助手 TASK 到基金助手，两跳共用 rootTaskId 且三层任务独立；
- Jaeger 与 Loki 能以同一 traceId 关联完整链路，主体引用未进入回执、日志、Trace 或指标；
- 并发重复信封只建立一个目标任务；
- Static Plan 规则锚定为余额到基金，第一步事实持久化；
- 手机银行助手容器重启后从游标继续第二步，第一步没有重放，最终聚合回复完整。
