package com.example.demo.payment.repository;

import com.example.demo.payment.domain.PaymentOrder;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付订单仓储（内存实现，生产环境替换为 JPA）。
 * saveIfAbsent 利用 ConcurrentHashMap 原子语义实现幂等插入。
 */
@Repository
public class PaymentOrderRepository {

    private final ConcurrentHashMap<String, PaymentOrder> store = new ConcurrentHashMap<>();

    /** 幂等写入：outTradeNo 已存在则不覆盖。返回 true 表示首次插入。 */
    public boolean saveIfAbsent(PaymentOrder order) {
        return store.putIfAbsent(order.getOutTradeNo(), order) == null;
    }

    public void save(PaymentOrder order) {
        store.put(order.getOutTradeNo(), order);
    }

    public Optional<PaymentOrder> findByOutTradeNo(String outTradeNo) {
        return Optional.ofNullable(store.get(outTradeNo));
    }
}
