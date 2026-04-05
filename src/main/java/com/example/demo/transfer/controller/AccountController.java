package com.example.demo.transfer.controller;

import com.example.demo.transfer.common.ApiResponse;
import com.example.demo.transfer.domain.Account;
import com.example.demo.transfer.domain.Ledger;
import com.example.demo.transfer.dto.CreateAccountRequest;
import com.example.demo.transfer.dto.DepositRequest;
import com.example.demo.transfer.service.AccountService;
import com.example.demo.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 账户管理接口。
 *
 * <pre>
 * POST   /api/accounts                        创建账户
 * GET    /api/accounts/{accountId}            查询账户信息
 * POST   /api/accounts/{accountId}/deposit    充值（测试用）
 * GET    /api/accounts/{accountId}/ledger     查询账务流水
 * </pre>
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;
    private final TransferService transferService;

    @PostMapping
    public ApiResponse<Account> createAccount(@RequestBody CreateAccountRequest request) {
        return ApiResponse.success(accountService.createAccount(request));
    }

    @GetMapping("/{accountId}")
    public ApiResponse<Account> getAccount(@PathVariable String accountId) {
        return ApiResponse.success(accountService.getAccount(accountId));
    }

    @PostMapping("/{accountId}/deposit")
    public ApiResponse<Account> deposit(@PathVariable String accountId,
                                        @RequestBody DepositRequest request) {
        return ApiResponse.success(accountService.deposit(accountId, request.getAmount()));
    }

    @GetMapping("/{accountId}/ledger")
    public ApiResponse<List<Ledger>> getLedger(@PathVariable String accountId) {
        return ApiResponse.success(transferService.queryLedger(accountId));
    }
}
