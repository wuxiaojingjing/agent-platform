# ADR-011：回复策略与小 i 外部证据边界

> 状态：Accepted / Implemented  
> 日期：2026-08-02

## 决策

1. 回复模式固定为 `TEMPLATE / MODEL_SELECT / POLISH / GENERATE`，统一经过
   `ResponsePlanner -> ResponseRealizer -> AnswerAudit -> ResponseEnvelope`。
2. 模型只生成或选择面客文字，不得改变 Decision、ResponsePhase、Runtime 状态、ResponseAction、
   ref/version、金额、收款人、账户/卡片、确认强度、风险提示或幂等状态。
3. 模型、Schema、事实对齐或审计失败时回退已审批模板。默认模式为 TEMPLATE；模型策略必须显式配置。
4. 回复策略按 tenant、Agent、scene、ResponsePhase 匹配；本地/测试可通过 AssetStore 热更新，
   生产控制台禁止直写，只生成 Git/MR 配置变更。
5. 小 i 黑盒输出先转换为 `XiaoiExternalEvidence`。rawScore 只保留原值，不与规则、BM25 或向量分直接相加。
6. 小 i 的知识、菜单、服务、反问和默认回复分型；任何执行授权只来自当前 `CapabilityCard`。
7. `CapabilityCard` 的 implementationStatus 与发布 status 分离；SCAFFOLD 必须 DISABLED，
   fallbackCapabilityIds 只描述受控降级候选，不授予执行权。

## 后果

- 用户看到的文本、工具/Agent 已提交事实与模型上下文使用同一可见历史；模型不接收思维过程。
- 未建设能力依次尝试已配置菜单、审批知识和 `HANDOFF + CAPABILITY_NOT_OPEN`。
- 没有真实小 i 外部接口契约时，只交付资产内化、台账和证据 Schema，不伪造在线 Provider。

## 验证

- 四种 RenderMode、模型不可用、非法模板选择、虚构数字/URL、风险提示与确认降级均有测试。
- 回复策略文件热更新后 AssetStore 版本变化且新模式立即可解析；生产写入接口返回 403。
- 小 i 首批 6 条审批答案可命中，21 条台账均有 lineage，损坏/占位答案不能进入运行知识。
- Loop 的四种 RenderMode 使用同一策略解析入口；模型只接收已进入用户可见摘要的事实，内部 Map、
  Planner 文本和未展示 Observation 不进入面客回复。
- 六条审批答案均通过 `/api/v1/chat` 的 `DIRECT_KNOWLEDGE + STANDARD_ANSWER` 入口用例；未建设能力
  同时覆盖结构化菜单降级、审批知识降级和 `HANDOFF + CAPABILITY_NOT_OPEN`。
