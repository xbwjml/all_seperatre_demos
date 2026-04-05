package com.example.demo.transfer.repository;

import com.example.demo.transfer.domain.TransferOrder;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 转账单仓储（内存实现）。
 * saveIfAbsent 利用 ConcurrentHashMap 的原子语义实现幂等插入，
 * 生产环境对应数据库 transferNo 唯一索引 + INSERT IGNORE / ON CONFLICT。
 */
@Repository
public class TransferOrderRepository {

    private final ConcurrentHashMap<String, TransferOrder> store = new ConcurrentHashMap<>();

    /**
     * 幂等写入：若 transferNo 已存在则不覆盖。
     *
     * @return true=首次插入成功；false=已存在（重复请求）
     */
    public boolean saveIfAbsent(TransferOrder order) {
        return store.putIfAbsent(order.getTransferNo(), order) == null;
    }

    public void save(TransferOrder order) {
        store.put(order.getTransferNo(), order);
    }

    public Optional<TransferOrder> findByTransferNo(String transferNo) {
        return Optional.ofNullable(store.get(transferNo));
    }
}
