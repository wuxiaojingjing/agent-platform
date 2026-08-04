package com.huawei.finance.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 慢路径配置。
 *
 * <p>{@link #isEnabled()} 默认 {@code true}（ADR-004）：多意图默认走 DeepAgent 精化，
 * 失败回退规则拆解。关掉则只落 RULE 计划，便于回归与压测对比。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.slowpath")
public class SlowPathProperties {

    public enum ExecutionMode {
        DISABLED, CONFIRM_EACH, AUTO_READ_ONLY
    }

    private boolean enabled = true;

    /** 规划用的模型。与仲裁分开配，两者的取舍不同：仲裁要快，规划要能推。 */
    private String model = "Qwen/Qwen2.5-7B-Instruct";

    /**
     * DeepAgent / 内层推理轮次上限。每一轮是一次模型往返，这是慢路径的成本闸门
     * （与快路径的往返序列分开看，见 ADR-003）。
     */
    private int maxIterations = 6;

    /** 交给规划器的候选能力上限。全量塞进提示词既超预算，也会让模型挑花眼。 */
    private int maxCandidates = 8;

    /**
     * DeepAgent Workspace 根目录。空则用 {@code java.io.tmpdir/agent-platform-slowpath-workspace}。
     * 进程内复用，禁止每请求建删（ADR-004）。
     */
    private String workspacePath = "";

    /** 默认不自动执行；产品和本地 Extension 可显式启用只读自动推进。 */
    private ExecutionMode executionMode = ExecutionMode.CONFIRM_EACH;

    private int maxAutoSteps = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode == null ? ExecutionMode.CONFIRM_EACH : executionMode;
    }

    public int getMaxAutoSteps() {
        return maxAutoSteps;
    }

    public void setMaxAutoSteps(int maxAutoSteps) {
        this.maxAutoSteps = Math.max(1, Math.min(5, maxAutoSteps));
    }
}
