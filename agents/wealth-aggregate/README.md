# wealth-aggregate

Agent 身份、角色和领域以 `agent.yaml` 为准。业务资产放在 `assets/`；只有需要实现平台 SPI 或领域能力时才创建或修改 `backend/`。

- `eval/`：该 Agent 的评测集与验收说明
- `deploy/`：该 Agent 的部署参数与环境说明
- Agent 间调用：只通过 A2A Gateway，不直接依赖其他 Agent 的实现

