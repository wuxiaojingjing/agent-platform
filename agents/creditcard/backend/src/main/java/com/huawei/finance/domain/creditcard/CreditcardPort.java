package com.huawei.finance.domain.creditcard;

public interface CreditcardPort {
    BillView bill(String principalRef, String cardRef);
    OperationReceipt repay(RepayCommand command);
    OperationReceipt replace(ReplaceCommand command);
    record BillView(String billAmount, String dueDate) { }
    record RepayCommand(String principalRef, String amount, String idempotencyKey) { }
    record ReplaceCommand(String principalRef, String cardType, String idempotencyKey) { }
    record OperationReceipt(String serialNo, String amount, String cardTypeName) { }
}
