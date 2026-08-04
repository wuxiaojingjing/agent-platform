package com.huawei.finance.orchestrator.guardrail;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 基线护栏的判定参数。
 *
 * <p><b>把它做成配置，不代表一个全局限额就够用了。</b>真实的单笔上限是按用户等级、
 * 渠道、当日累计、风控实时评分算出来的，不是一个常数——写成 yml 里的一个数字，
 * 只是把「写死在 Java 里」换成了「写死在配置里」，行内照样对不上自己的风控口径。
 *
 * <p>真正的答案是实现 {@link GuardrailHook} 接管整段判定（见 README 扩展点一节）。
 * 这里外置的意义只有一个：让行外验证和联调能改这个数，而不必重新编译基线。
 */
@ConfigurationProperties(prefix = "huawei.finance.agent.guardrail")
public class GuardrailProperties {

    /**
     * 单笔转账上限。
     *
     * <p>用 {@code BigDecimal} 而不是 {@code long}：金额比较一旦经过 double，
     * 就会出现「49999.999999 通过、50000.000001 也通过」这类靠肉眼看不出来的越限，
     * 而这是护栏，判错的方向只有一种是可接受的。
     */
    private BigDecimal singleTransferLimit = new BigDecimal("50000");

    public BigDecimal getSingleTransferLimit() {
        return singleTransferLimit;
    }

    public void setSingleTransferLimit(BigDecimal singleTransferLimit) {
        this.singleTransferLimit = singleTransferLimit;
    }
}
