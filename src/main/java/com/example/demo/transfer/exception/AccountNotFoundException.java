package com.example.demo.transfer.exception;

public class AccountNotFoundException extends TransferException {

    public AccountNotFoundException(String accountId) {
        super("账户不存在: " + accountId);
    }
}
