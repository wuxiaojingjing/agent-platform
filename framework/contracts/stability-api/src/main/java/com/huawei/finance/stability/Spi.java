package com.huawei.finance.stability;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行内**可以实现**的扩展点。基线不会往里加抽象方法。
 *
 * <p>「不加抽象方法」是这个标注的全部重量所在。加一个抽象方法，所有行内实现在升级时
 * 一起编译不过；而这类破坏往往是在基线侧毫无察觉的情况下发生的——本模块内的实现类
 * 顺手一起改了，用例照样全绿，破的是仓库外面那些看不见的实现。要扩展只能加 default 方法。
 *
 * <p>标了 {@code @Spi} 的类型必须是接口。抽象类会把继承结构也变成承诺的一部分，
 * 而那些结构（构造器、受保护字段、模板方法的调用顺序）远比一组方法签名难保持稳定。
 * 这条由 ArchUnit 守住。
 *
 * <p>每个 {@code @Spi} 都应当有对应的 {@code @Bean} + {@code @ConditionalOnMissingBean}
 * 让位装配，否则行内实现了也装不进去。这条也由 ArchUnit 守住。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Spi {
}
