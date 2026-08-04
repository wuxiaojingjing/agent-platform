package com.huawei.finance.domain.payroll;
public interface PayrollStatusPort { StatusView status(String principalRef); record StatusView(String status,String lastArrivalDate,String employer){} }
