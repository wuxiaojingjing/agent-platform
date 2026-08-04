# promptopt — 仲裁与上下文改写提示词优化器

给快路径的 `arbitration-skill.yaml` 找一版更好的 `system` 段。思路取自 SkillOpt：
**在冻结轨迹上迭代编辑，用验证门控采纳，产出候选而不是直接上线。**

它**不是运行时模块**。`tools/` 整个目录都不在 `agent-platform` 的 `<modules>` 里，
`mvn package` 打不到它，它的依赖也进不了生产 classpath。

## 为什么要冻结轨迹

优化提示词时必须能回答一个问题：这一版变好了，是提示词的功劳，还是这次召回刚好抖出了更好的候选？

这不是假想的顾虑。第二档评测实测到「这个月要还多少」跨次跑会在只读能力与资金动作能力之间来回换。
在会抖的信号上爬坡，涨的分多半是噪声。

所以流程分两步：先把真召回那一次交给模型的 `user` 段**逐字**录下来（`TrajectoryCaptureTest`），
之后每轮只换 `system`。召回成了常量，差异就只能来自提示词。

代价是**轨迹会过期**：资产一改召回就变。每条轨迹带 `assetVersion`，版本不符时工具直接抛错而不是
警告后继续——继续跑会输出一份看起来很正常、实际在优化一个已不存在的召回态的报告。

## 跑

```bash
# 1. 装好它依赖的运行时模块
cd agent-platform
mvn -pl framework/bom/agent-bom,framework/contracts/agent-api,framework/registry/asset-registry,infrastructure/model/model-openai-compatible -am install -DskipTests

# 2. 冻结轨迹（要 OpenSearch 起着，且要 SILICONFLOW_API_KEY）
source scripts/env.local.sh
mvn -pl framework/intent-engine/intent-fastpath test -Dtest=TrajectoryCaptureTest      # → eval/trajectories.json

# 3. 优化（参数是最多试几轮，默认 3）
cd tools/promptopt
source ../../scripts/env.local.sh
mvn -q compile exec:java -Dexec.mainClass=com.huawei.finance.tool.promptopt.PromptOptimizer -Dexec.args=3
```

产出在 `out/arbitration-skill.candidate.yaml`，**没有生效**。采纳要人来做：逐字读过 `system` 段、
只把 `system` 段贴回资产（文件里没有 `user` 段，整文件覆盖会弄丢占位符模板）、手工升 `version`、
重跑快路径全量用例与两档评测。

## 上下文改写模式

该模式使用与本地应用一致的 `deepseek-v4-flash` 和 `contextual-query-output` Schema，
并额外门控越界引用、旧余额派生金额和 resolution/ref/slot 原子契约。

```bash
cd tools/promptopt
source ../../scripts/env.local.sh
mvn -q compile exec:java \
  -Dexec.mainClass=com.huawei.finance.agent.promptopt.ContextTrajectoryCapture
mvn -q compile exec:java \
  -Dexec.mainClass=com.huawei.finance.agent.promptopt.ContextPromptOptimizer \
  -Dexec.args=3
```

冻结轨迹写入 `agents/mobile-banking-assistant/eval/context-rewrite-trajectories.json`；候选写入
`tools/promptopt/out/context-rewrite-skill.candidate.yaml`，同样不会自动上线。

一轮要在全部轨迹上跑一遍仲裁，当前 24 条轨迹约 1 分钟、几分钱。轮数就是预算，所以没有「跑到收敛」这个选项。

## 门（`OptimizerGuard`）

一个只看分数的优化器，最省力的涨分方式是把安全约束删掉——「拿不准留空」会让模型在边界样本上
选 CLARIFY，而 CLARIFY 常常不是真值；删了它那几条立刻变对。它不是「想」这么做，
它只是在按梯度走，而这个方向有分。所以：

| 门 | 拦什么 | 为什么是这个形状 |
| --- | --- | --- |
| 安全锚句 | 五条不可违反规则必须**逐字**在 | 关键词判断拦不住一句意思相反的话；让模型自己保证则无从检验 |
| 契约原因码 | 不得发明 Schema 枚举之外的原因码 | 实测它真加过 `R2_MISSING_CONFIRMATION`，且那版分数还涨了——模型恰好没用它。这是最坏的一类候选：带着哑弹通过了所有验证 |
| 长度上限 3000 字 | 提示词膨胀 | 超预算时 `buildWithinBudget` 裁掉的是**候选**，于是「写得更细」在线上变成「候选变少」 |
| 结构不许退 | 不合 Schema、越界选择、R2 漏确认三项变多 | 分数是平均量，这三项不是 |
| 至少 +2 条 | 噪声级的涨分 | 同一段提示词两次打分实测差 1 条。门槛设成 1，优化器每轮都有约五成机会「找到改进」 |

## 一次实跑

现状 14/23，三轮后 18/23，R2 漏确认（模型层）5 → 0。第 2、3 轮各涨 1 条，都被噪声门拒了。
被采纳的那一版改动是在【出口选择】里明确资金动作类能力必须走 `CONFIRMATION_REQUIRED`。

## 关于「R2 漏确认」这个指标

它数的是**模型原始输出**里把资金动作能力判成无确认直出的条数，**不是线上漏洞**。
线上这份输出还要过 `FailSafeGuard`：风险等级是能力卡上的属性，不接受模型判断，R2 一律改写成
`CONFIRMATION_REQUIRED`。全量评测里 R2 无确认直出始终是 0。

那为什么还数它：兜底是最后一道，不是第一道。这个数字衡量的是纵深防御第一层的健康度——
一旦哪天有人引入一条绕过 `FailSafeGuard` 的路径，它就会立刻变成真实风险。
所以门只要求它**不许变多**，而不是必须为零（现状本来就不是零）。
