package com.huawei.finance.runtime.loop;

import com.huawei.finance.orchestrator.context.TaskContextModels.RuntimeType;
import com.huawei.finance.orchestrator.continuation.ContinuationContracts.*;
import com.huawei.finance.orchestrator.continuation.RuntimeContinuationPort;
import com.huawei.finance.orchestrator.loop.AgentLoopRepository;

public class LoopContinuationPort implements RuntimeContinuationPort {
    private final LoopContinuationViewProvider views;
    private final LoopResumeAdapter resumes;
    public LoopContinuationPort(AgentLoopRepository loops){
        this.views=new LoopContinuationViewProvider(loops);
        this.resumes=new LoopResumeAdapter(loops);
    }
    @Override public RuntimeType runtimeType(){return RuntimeType.AGENT_LOOP;}
    @Override public Snapshot describe(String tenant,String agent,String ref){
        return views.describe(tenant,agent,ref);
    }
    @Override public Snapshot resume(String tenant,String agent,String ref,Resolution resolution,long version){
        resumes.apply(tenant,agent,ref,resolution,version);
        return views.describe(tenant,agent,ref);
    }
}
