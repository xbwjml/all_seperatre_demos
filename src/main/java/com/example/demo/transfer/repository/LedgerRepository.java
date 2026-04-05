package com.example.demo.transfer.repository;

import com.example.demo.transfer.domain.Ledger;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 账务流水仓储（内存实现，只增不改）。
 * CopyOnWriteArrayList 保证按账户维度的流水列表并发安全读取。
 */
@Repository
public class LedgerRepository {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Ledger>> store = new ConcurrentHashMap<>();

    public void save(Ledger ledger) {
        store.computeIfAbsent(ledger.getAccountId(), k -> new CopyOnWriteArrayList<>())
             .add(ledger);
    }

    public List<Ledger> findByAccountId(String accountId) {
        CopyOnWriteArrayList<Ledger> list = store.get(accountId);
        return list == null ? List.of() : List.copyOf(list);
    }
}
