package com.huawei.finance.contracts.validation;

/** 本方产出的数据不符合自身契约。这属于代码缺陷，不应被 catch 后降级掩盖。 */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }
}
