# Agent Template

复制模板后首先修改 `agent.yaml`。`agent.id` 必须显式声明且全局唯一，不能从目录名推导。

模板默认是 `implementation.mode=scaffold`，只能占位并返回 `DOMAIN_NOT_OPEN`。真正可执行的 Java 子 Agent
应切换为 `extension`、声明 Maven artifact，并在 `backend/` 实现领域能力；独立或异构 Agent 实现
`/a2a/v2/inbound` HTTP 契约。完整步骤见
[开发者自建子 Agent 接入指南](../docs/开发者自建子Agent接入指南_v0.1.md)。
