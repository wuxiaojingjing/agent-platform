package com.huawei.finance.domain.loan;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
@AutoConfiguration
public class LoanDomainConfiguration {
    @Bean @ConditionalOnMissingBean(LoanCatalogPort.class)
    LoanCatalogPort loanCatalogPort(RestClient.Builder builder, @Value("${huawei.finance.backends.loan.base-url:http://banking-systems-simulator:8090}") String baseUrl, @Value("${huawei.finance.backends.loan.timeout:2s}") Duration timeout) {
        var factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(timeout); factory.setReadTimeout(timeout);
        var client=builder.baseUrl(baseUrl).requestFactory(factory).build();
        return () -> product(client.get().uri("/api/loans/products").retrieve().body(Map.class));
    }
    @Bean @ConditionalOnMissingBean(name="loanDomainAgent") TechDomainAgent loanDomainAgent(LoanCatalogPort port) { return new LoanDomainAgent(port); }
    private static LoanCatalogPort.ProductView product(Map<?,?> m) { if(m==null) throw new IllegalStateException("empty loan response"); return new LoanCatalogPort.ProductView(v(m,"productCode"),v(m,"name"),v(m,"domain"),v(m,"riskLevel"),v(m,"returnRate"),v(m,"term")); }
    private static String v(Map<?,?> m,String k){Object value=m.get(k);return value==null?"":String.valueOf(value);}
}
