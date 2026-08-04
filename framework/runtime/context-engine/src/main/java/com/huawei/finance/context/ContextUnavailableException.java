package com.huawei.finance.context;

/**
 * 上下文不可读。
 *
 * <p>存在的意义是把「没有历史」和「读不到历史」分成两件事。前者是新会话的正常形态，
 * 后者意味着我们不知道用户刚才确认过什么——如果两者都表现为一个空列表，
 * 「上下文异常时停止有副作用操作」（v0.7 §4.0）在代码里就没有落点。
 *
 * <p>不设计成受检异常：真正的兜底在 {@link ContextLeaseCompiler}，它一定会返回一份租约，
 * 读不到时返回不可信的降级租约。调用方拿到的是 {@code allowsSideEffects() == false}，
 * 而不是一个可以忘记 catch 的异常。
 */
public class ContextUnavailableException extends RuntimeException {

    public ContextUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
