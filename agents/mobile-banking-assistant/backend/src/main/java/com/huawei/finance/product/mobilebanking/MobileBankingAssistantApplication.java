package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.common.context.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 手机银行助手 Agent 中控启动类。
 *
 * <p>不叫「意图引擎」：本进程装的远不止意图识别——任务状态机、护栏与幂等键、
 * 回复编排都在里面，而意图识别只是入口那一层。意图引擎是其中一层的名字
 * （快路径 {@code intent-fastpath} + 尚未实现的慢路径主 Agent），不是整体的名字。
 *
 * <p>只扫 {@code com.huawei.finance.product.mobilebanking}。各库模块不再靠组件扫描生效，而是各自带
 * {@code AutoConfiguration.imports}，像标准 starter 那样被自动配置装进来——这是
 * 「使用方能覆盖基线 Bean」的前提，只有自动配置才在使用方配置之后评估。
 *
 * <p>收窄扫描范围不是顺手整理。留着 {@code com.huawei.finance} 的话，日后有人在库模块里加一个
 * {@code @Component}，在本工程能跑通，而银行把模块嵌进他们自己的应用时那个 Bean 就消失了
 * ——因为他们的扫描根是行内包名。让库模块彻底不依赖扫描，这类问题在本工程就会暴露。
 */
@SpringBootApplication(scanBasePackages = "com.huawei.finance.product.mobilebanking")
@EnableConfigurationProperties(AgentProperties.class)
public class MobileBankingAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(MobileBankingAssistantApplication.class, args);
    }
}
