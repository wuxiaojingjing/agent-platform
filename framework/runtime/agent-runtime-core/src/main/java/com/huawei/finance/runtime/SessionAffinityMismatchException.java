package com.huawei.finance.runtime;

/**
 * FP-66：会话已绑定其他实例，本实例不得续办（ADR-004）。
 */
public final class SessionAffinityMismatchException extends RuntimeException {

    private final String sessionId;
    private final String localInstanceId;

    public SessionAffinityMismatchException(String sessionId, String localInstanceId) {
        super("会话 " + sessionId + " 已绑定其他实例，本机 " + localInstanceId + " 拒绝续办（FP-66）");
        this.sessionId = sessionId;
        this.localInstanceId = localInstanceId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String localInstanceId() {
        return localInstanceId;
    }
}
