package com.huawei.finance.common.context;

/**
 * 请求上下文的线程持有者。
 *
 * <p>只在同步请求线程内有效。快路径与中控目前都是同步执行，一旦某段逻辑改为异步或
 * 提交线程池，必须显式传递 {@link RequestContext} 而不是依赖这里，否则网关往返计数会失真。
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static void set(RequestContext ctx) {
        CURRENT.set(ctx);
    }

    public static RequestContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
