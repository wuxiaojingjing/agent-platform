package com.huawei.finance.sample.oj.e2e;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 端到端用的 Agent Server 部署件，形状与行内真实那个一致。
 *
 * <p>它只做三件事：起 Web 容器、让自动装配把 OJ 的 {@code /v1/query} 与
 * {@code OpenJiuwenAgentServerConfiguration} 装进来、提供领域实现。也就是说行内落地时
 * 需要写的东西和这个类差不多多——这一点本身就是要验的：
 * 如果接一次 OJ 得在部署件里手写一堆胶水，那这个模块就没有做到位。
 *
 * <p>{@link EnableAutoConfiguration} 由 {@link SpringBootApplication} 带上，
 * 组件扫描范围限定在本包，避免把测试里那些故意不合规的实现也扫进来。
 */
@SpringBootApplication
public class OjTestServerApplication {
}
