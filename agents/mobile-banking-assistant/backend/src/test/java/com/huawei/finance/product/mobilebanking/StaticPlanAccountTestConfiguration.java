package com.huawei.finance.product.mobilebanking;

import com.huawei.finance.domain.account.AccountPort;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Static Plan 端到端测试使用的确定性账户后端。 */
@TestConfiguration(proxyBeanMethods = false)
class StaticPlanAccountTestConfiguration {

    @Bean
    @Primary
    AccountPort staticPlanAccountPort() {
        return new AccountPort() {
            @Override
            public AccountView accountView(String principalRef) {
                return new AccountView(List.of(new CardView(1, "工资卡", "12845.60")));
            }

            @Override
            public List<TransactionView> transactions(String principalRef) {
                return List.of();
            }
        };
    }
}
