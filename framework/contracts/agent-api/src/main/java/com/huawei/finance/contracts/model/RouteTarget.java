package com.huawei.finance.contracts.model;

import com.huawei.finance.stability.Api;

@Api
public record RouteTarget(Type type, String id) {
    public enum Type {
        KNOWLEDGE, MENU, CAPABILITY, WORKFLOW, AGENT, TASK, LOOP
    }
}
