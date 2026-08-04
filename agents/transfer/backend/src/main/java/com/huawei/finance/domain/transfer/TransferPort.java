package com.huawei.finance.domain.transfer;

public interface TransferPort {
    TransferReceipt submit(TransferCommand command);
    record TransferCommand(String principalRef, String payee, String amount,
                           String fromAccount, String idempotencyKey) { }
    record TransferReceipt(String payee, String amount, String fromAccount,
                           String serialNo, String finishedAt) { }
}
