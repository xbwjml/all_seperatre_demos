package com.example.demo.transfer.service;

import com.example.demo.transfer.domain.Account;
import com.example.demo.transfer.dto.CreateAccountRequest;
import com.example.demo.transfer.exception.AccountNotFoundException;
import com.example.demo.transfer.exception.TransferException;
import com.example.demo.transfer.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    public Account createAccount(CreateAccountRequest request) {
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            throw new TransferException("userId 不能为空");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new TransferException("账户名称不能为空");
        }

        Account account = new Account();
        account.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        account.setUserId(request.getUserId());
        account.setName(request.getName());
        account.setBalance(request.getInitialBalance() != null
                ? request.getInitialBalance() : BigDecimal.ZERO);
        account.setFrozen(BigDecimal.ZERO);
        account.setVersion(0);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());

        accountRepository.save(account);
        log.info("账户创建成功: id={}, userId={}, 初始余额={}", account.getId(), account.getUserId(), account.getBalance());
        return account;
    }

    public Account getAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * 充值（仅用于测试，生产环境需走支付流程）。
     */
    public Account deposit(String accountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new TransferException("充值金额必须大于 0");
        }
        Account account = getAccount(accountId);
        account.credit(amount);
        accountRepository.save(account);
        log.info("账户充值: accountId={}, amount={}, 充值后余额={}", accountId, amount, account.getBalance());
        return account;
    }
}
