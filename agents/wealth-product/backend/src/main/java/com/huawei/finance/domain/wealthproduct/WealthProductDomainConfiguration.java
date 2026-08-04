package com.huawei.finance.domain.wealthproduct;
import com.huawei.finance.contracts.port.TechDomainAgent;
import java.time.Duration; import java.util.Map;
import org.springframework.beans.factory.annotation.Value; import org.springframework.boot.autoconfigure.AutoConfiguration; import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean; import org.springframework.context.annotation.Bean; import org.springframework.http.client.SimpleClientHttpRequestFactory; import org.springframework.web.client.RestClient;
@AutoConfiguration
public class WealthProductDomainConfiguration {
    @Bean @ConditionalOnMissingBean(WealthProductPort.class) WealthProductPort wealthProductPort(RestClient.Builder builder,@Value("${huawei.finance.backends.wealth-product.base-url:http://banking-systems-simulator:8090}") String baseUrl,@Value("${huawei.finance.backends.wealth-product.timeout:2s}") Duration timeout){var f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(timeout);f.setReadTimeout(timeout);var c=builder.baseUrl(baseUrl).requestFactory(f).build();return capabilityId->product(c.get().uri("/api/wealth/products?capabilityId={capabilityId}",capabilityId).retrieve().body(Map.class));}
    @Bean @ConditionalOnMissingBean(name="wealthProductDomainAgent") TechDomainAgent wealthProductDomainAgent(WealthProductPort port){return new WealthProductDomainAgent(port);}
    private static WealthProductPort.ProductView product(Map<?,?>m){if(m==null)throw new IllegalStateException("empty wealth product response");return new WealthProductPort.ProductView(v(m,"productCode"),v(m,"name"),v(m,"domain"),v(m,"riskLevel"),v(m,"returnRate"),v(m,"term"));} private static String v(Map<?,?>m,String k){Object value=m.get(k);return value==null?"":String.valueOf(value);}
}
