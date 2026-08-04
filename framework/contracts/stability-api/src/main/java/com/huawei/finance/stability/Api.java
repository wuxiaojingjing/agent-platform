package com.huawei.finance.stability;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行内**可以调用**的类型。签名与语义在大版本内保持兼容。
 *
 * <p>与 {@link Spi} 的区别是调用方向：{@code @Api} 是行内调基线，{@code @Spi} 是基线回调行内。
 * 一个类型可能两者都是——{@code UnifiedTask} 既由行内读取，也由基线构造后传下去。
 * 这种情况标 {@code @Api} 即可，{@link Spi} 只用于「行内要实现的接口」。
 *
 * <p>{@code RUNTIME} 保留：ArchUnit 用例要在运行期读到它才能守住这条线。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface Api {
}
