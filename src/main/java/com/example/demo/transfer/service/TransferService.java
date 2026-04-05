package com.example.demo.transfer.service;

import com.example.demo.transfer.domain.Account;
import com.example.demo.transfer.domain.Ledger;
import com.example.demo.transfer.domain.TransferOrder;
import com.example.demo.transfer.domain.TransferStatus;
import com.example.demo.transfer.dto.TransferRequest;
import com.example.demo.transfer.dto.TransferResult;
import com.example.demo.transfer.exception.AccountNotFoundException;
import com.example.demo.transfer.exception.InsufficientBalanceException;
import com.example.demo.transfer.exception.TransferException;
import com.example.demo.transfer.repository.AccountRepository;
import com.example.demo.transfer.repository.LedgerRepository;
import com.example.demo.transfer.repository.TransferOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 转账核心服务。
 *
 * <p>关键设计要点：
 * <ol>
 *   <li><b>幂等性</b>：以 transferNo 为唯一键，ConcurrentHashMap.putIfAbsent 保证只执行一次</li>
 *   <li><b>防死锁</b>：始终按账户 ID 字典序固定加锁顺序，避免 A→B 与 B→A 互相等待</li>
 *   <li><b>原子性</b>：扣款与收款在同一把锁保护范围内执行（内存实现；生产环境用数据库事务）</li>
 *   <li><b>复式记账</b>：每笔转账写两条流水（借/贷），账户余额可随时从流水重算</li>
 *   <li><b>状态机</b>：PENDING → PROCESSING → SUCCESS/FAILED，单向流转</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferOrderRepository transferOrderRepository;
    private final LedgerRepository ledgerRepository;

    // -------------------------------------------------------------------------
    // 转账入口
    // -------------------------------------------------------------------------

    public TransferResult transfer(TransferRequest request) {
        validateRequest(request);

        // 1. 快速校验账户存在（在锁外）
        Account from = accountRepository.findById(request.getFromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.getFromAccountId()));
        Account to = accountRepository.findById(request.getToAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.getToAccountId()));

        // 2. 幂等写入转账单；若已存在直接返回已有结果
        TransferOrder order = buildOrder(request);
        boolean inserted = transferOrderRepository.saveIfAbsent(order);
        if (!inserted) {
            log.info("重复转账请求，transferNo={}", request.getTransferNo());
            return TransferResult.from(transferOrderRepository.findByTransferNo(request.getTransferNo()).get());
        }

        // 3. 按固定顺序（字典序）加锁，防止 A→B 与 B→A 并发时死锁
        String id1 = from.getId();
        String id2 = to.getId();
        ReentrantLock firstLock  = accountRepository.getLock(id1.compareTo(id2) <= 0 ? id1 : id2);
        ReentrantLock secondLock = accountRepository.getLock(id1.compareTo(id2) <= 0 ? id2 : id1);

        firstLock.lock();
        try {
            secondLock.lock();
            try {
                return doTransfer(order, from, to);
            } finally {
                secondLock.unlock();
            }
        } finally {
            firstLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // 核心执行（必须在锁内调用）
    // -------------------------------------------------------------------------

    private TransferResult doTransfer(TransferOrder order, Account from, Account to) {
        // 推进状态：PENDING → PROCESSING
        order.setStatus(TransferStatus.PROCESSING);
        order.setUpdatedAt(LocalDateTime.now());
        transferOrderRepository.save(order);

        try {
            // 在锁内再次校验余额（防止锁外校验通过、锁内余额已变化）
            if (from.getAvailableBalance().compareTo(order.getAmount()) < 0) {
                markFailed(order, String.format("余额不足，可用余额: %s", from.getAvailableBalance().toPlainString()));
                throw new InsufficientBalanceException(from.getId(), from.getAvailableBalance(), order.getAmount());
            }

            // 原子扣减 + 收款
            from.deduct(order.getAmount());
            to.credit(order.getAmount());

            // 复式记账：一借一贷
            writeLedger(from, order, order.getAmount().negate(), "转出至账户 " + to.getId());
            writeLedger(to,   order, order.getAmount(),          "从账户 " + from.getId() + " 转入");

            // 推进状态：PROCESSING → SUCCESS
            order.setStatus(TransferStatus.SUCCESS);
            order.setUpdatedAt(LocalDateTime.now());
            transferOrderRepository.save(order);

            log.info("转账成功: transferNo={}, from={}, to={}, amount={}",
                    order.getTransferNo(), from.getId(), to.getId(), order.getAmount());
            return TransferResult.from(order);

        } catch (InsufficientBalanceException e) {
            throw e;
        } catch (Exception e) {
            markFailed(order, e.getMessage());
            throw new TransferException("转账执行异常: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // 查询
    // -------------------------------------------------------------------------

    public TransferOrder queryByTransferNo(String transferNo) {
        return transferOrderRepository.findByTransferNo(transferNo)
                .orElseThrow(() -> new TransferException("转账记录不存在: " + transferNo));
    }

    public List<Ledger> queryLedger(String accountId) {
        // 账户存在性校验
        accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return ledgerRepository.findByAccountId(accountId);
    }

    // -------------------------------------------------------------------------
    // 私有方法
    // -------------------------------------------------------------------------

    private void writeLedger(Account account, TransferOrder order, BigDecimal amount, String description) {
        Ledger ledger = new Ledger();
        ledger.setId(UUID.randomUUID().toString());
        ledger.setAccountId(account.getId());
        ledger.setTransferNo(order.getTransferNo());
        ledger.setAmount(amount);
        ledger.setBalanceAfter(account.getBalance());
        ledger.setDescription(description);
        ledger.setCreatedAt(LocalDateTime.now());
        ledgerRepository.save(ledger);
    }

    private void markFailed(TransferOrder order, String reason) {
        order.setStatus(TransferStatus.FAILED);
        order.setFailReason(reason);
        order.setUpdatedAt(LocalDateTime.now());
        transferOrderRepository.save(order);
        log.warn("转账失败: transferNo={}, 原因={}", order.getTransferNo(), reason);
    }

    private TransferOrder buildOrder(TransferRequest request) {
        TransferOrder order = new TransferOrder();
        order.setId(UUID.randomUUID().toString());
        order.setTransferNo(request.getTransferNo());
        order.setFromAccountId(request.getFromAccountId());
        order.setToAccountId(request.getToAccountId());
        order.setAmount(request.getAmount());
        order.setRemark(request.getRemark());
        order.setStatus(TransferStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private void validateRequest(TransferRequest request) {
        if (request.getTransferNo() == null || request.getTransferNo().isBlank()) {
            throw new TransferException("transferNo 不能为空");
        }
        if (request.getFromAccountId() == null || request.getFromAccountId().isBlank()) {
            throw new TransferException("fromAccountId 不能为空");
        }
        if (request.getToAccountId() == null || request.getToAccountId().isBlank()) {
            throw new TransferException("toAccountId 不能为空");
        }
        if (request.getFromAccountId().equals(request.getToAccountId())) {
            throw new TransferException("不能向自己转账");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new TransferException("转账金额必须大于 0");
        }
        if (request.getAmount().scale() > 2) {
            throw new TransferException("金额最多保留两位小数");
        }
    }
}
