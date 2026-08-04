package com.huawei.finance.sample.workflow;

import com.huawei.finance.registry.asset.AgentAssetLocations;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 声明式办理流程的开关与资产位置。
 *
 * <p>默认关：一个进程要不要承接领域办理，是部署决定，不该因为依赖里出现了这个模块就自动生效。
 */
@ConfigurationProperties(prefix = "huawei.finance.sample.workflow")
public class WorkflowFlowProperties {

    /** 是否启用声明式办理流程。 */
    private boolean enabled = false;

    /**
     * 流程声明目录。留空则从工作目录向上定位仓库 {@code assets/flows}。
     *
     * <p>同 {@code huawei.finance.agent.registry.assets-path}：默认值不该编「进程从哪一层启动」，
     * 那会让每个部署各覆盖一次，而覆盖漏了的表现是流程一条都没加载、办理静默不生效。
     */
    private String flowsDir;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFlowsDir() {
        return flowsDir == null || flowsDir.isBlank()
                ? AgentAssetLocations.requireAssets().resolve("flows").toString()
                : flowsDir;
    }

    public void setFlowsDir(String flowsDir) {
        this.flowsDir = flowsDir;
    }
}
