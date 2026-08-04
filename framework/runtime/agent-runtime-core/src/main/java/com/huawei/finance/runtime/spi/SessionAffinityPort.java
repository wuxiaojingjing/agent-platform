package com.huawei.finance.runtime.spi;

import com.huawei.finance.common.context.RequestContext;

/**
 * 会话实例亲和端口（FP-66）。Redis 等实现留在基础设施 / 应用侧。
 */
public interface SessionAffinityPort {

    enum Outcome {
        OK,
        MISMATCH
    }

    String instanceId();

    Outcome claim(RequestContext ctx);

    Outcome assertOwner(RequestContext ctx);

    void release(RequestContext ctx);

    /** 无亲和实现：总是放行。 */
    SessionAffinityPort NONE = new SessionAffinityPort() {
        @Override
        public String instanceId() {
            return "none";
        }

        @Override
        public Outcome claim(RequestContext ctx) {
            return Outcome.OK;
        }

        @Override
        public Outcome assertOwner(RequestContext ctx) {
            return Outcome.OK;
        }

        @Override
        public void release(RequestContext ctx) {
        }
    };
}
