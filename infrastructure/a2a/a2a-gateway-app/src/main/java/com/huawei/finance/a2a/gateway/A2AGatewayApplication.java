package com.huawei.finance.a2a.gateway;

import com.huawei.finance.a2a.A2AConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({A2AConfiguration.class, GatewayRoutingConfiguration.class})
public class A2AGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(A2AGatewayApplication.class, args);
    }
}
