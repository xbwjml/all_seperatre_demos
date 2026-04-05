package com.example.demo.transfer.repository;

import com.example.demo.transfer.domain.Account;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 账户仓储（内存实现，生产环境替换为 JPA/MyBatis）。
 * 账户级别的 ReentrantLock 集中管理于此，避免锁对象散落在领域层。
 */
@Repository
public class AccountRepository {

    private final ConcurrentHashMap<String, Account> store = new ConcurrentHashMap<>();
    /** 账户 ID → 专属锁，确保同一账户的余额变更串行执行 */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void save(Account account) {
        store.put(account.getId(), account);
        locks.computeIfAbsent(account.getId(), k -> new ReentrantLock());
    }

    public Optional<Account> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public Collection<Account> findAll() {
        return store.values();
    }

    /**
     * 获取账户专属锁。转账服务使用此锁做固定顺序加锁，防止死锁。
     */
    public ReentrantLock getLock(String accountId) {
        return locks.computeIfAbsent(accountId, k -> new ReentrantLock());
    }
}
