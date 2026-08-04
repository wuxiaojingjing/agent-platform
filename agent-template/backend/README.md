# Backend

可选目录。当前可执行的通用 Host 子 Agent 使用 `implementation.mode=extension`，需要在这里创建 Maven 模块，
提供 `TechDomainAgent`、类型化业务 Port、自动配置和对应 TCK。不得复制平台 Runtime 或直接依赖父 Agent。

完整步骤见 [开发者自建子 Agent 接入指南](../../docs/开发者自建子Agent接入指南_v0.1.md)。
