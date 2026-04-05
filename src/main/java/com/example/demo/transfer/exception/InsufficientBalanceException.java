package com.example.demo.transfer.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends TransferException {

    public InsufficientBalanceException(String accountId, BigDecimal available, BigDecimal required) {
        super(String.format("账户 [%s] 余额不足，可用余额: %s，转账金额: %s",
                accountId, available.toPlainString(), required.toPlainString()));
    }
}
