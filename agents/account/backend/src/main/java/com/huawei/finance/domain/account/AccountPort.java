package com.huawei.finance.domain.account;

import java.util.List;

/** 账户系统业务边界。 */
public interface AccountPort {
    AccountView accountView(String principalRef);
    List<TransactionView> transactions(String principalRef);

    record AccountView(List<CardView> cards) {
        public AccountView { cards = cards == null ? List.of() : List.copyOf(cards); }
    }
    record CardView(int index, String alias, String availableBalance) { }
    record TransactionView(String date, String description, String amount) { }
}
