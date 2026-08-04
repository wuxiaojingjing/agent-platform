package com.huawei.finance.runtime.loop;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("huawei.finance.agent.loop")
public class AgentLoopProperties {
    private boolean enabled;
    private int maxIterations=6;
    private int maxCandidates=8;
    private int maxModelCalls=8;
    private int maxRepeatAction=1;
    private int maxDelegationDepth=3;
    private int deadlineSeconds=30;
    private int claimRecoverySeconds=60;
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public int getMaxIterations(){return maxIterations;} public void setMaxIterations(int v){maxIterations=v;}
    public int getMaxCandidates(){return maxCandidates;} public void setMaxCandidates(int v){maxCandidates=v;}
    public int getMaxModelCalls(){return maxModelCalls;} public void setMaxModelCalls(int v){maxModelCalls=v;}
    public int getMaxRepeatAction(){return maxRepeatAction;} public void setMaxRepeatAction(int v){maxRepeatAction=v;}
    public int getMaxDelegationDepth(){return maxDelegationDepth;} public void setMaxDelegationDepth(int v){maxDelegationDepth=v;}
    public int getDeadlineSeconds(){return deadlineSeconds;} public void setDeadlineSeconds(int v){deadlineSeconds=v;}
    public int getClaimRecoverySeconds(){return claimRecoverySeconds;} public void setClaimRecoverySeconds(int v){claimRecoverySeconds=v;}
}
