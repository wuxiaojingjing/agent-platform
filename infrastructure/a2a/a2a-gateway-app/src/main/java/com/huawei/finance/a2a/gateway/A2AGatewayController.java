package com.huawei.finance.a2a.gateway;

import com.huawei.finance.a2a.A2AGateway;
import com.huawei.finance.contracts.a2a.DelegationEnvelope;
import com.huawei.finance.contracts.a2a.DelegationReceipt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/a2a/v2")
public class A2AGatewayController {

    private final A2AGateway gateway;

    public A2AGatewayController(A2AGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/delegations")
    public DelegationReceipt dispatch(@RequestBody DelegationEnvelope envelope) {
        return gateway.dispatch(envelope);
    }
}
