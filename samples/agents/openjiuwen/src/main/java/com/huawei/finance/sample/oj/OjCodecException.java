package com.huawei.finance.sample.oj;

/**
 * 编解码失败。
 *
 * <p>抛出它意味着「这次调用的载荷不是本编解码器认得的」，而正确反应是**整单失败**，
 * 不是尽力解析。参见 {@link OjQueryCodec} 关于验信封的说明：能被误解读的那一类响应，
 * 恰好是最像正常结果的那一类。
 */
public class OjCodecException extends RuntimeException {

    public OjCodecException(String message) {
        super(message);
    }

    public OjCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
