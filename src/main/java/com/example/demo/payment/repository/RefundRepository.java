package com.example.demo.payment.repository;

import com.example.demo.payment.domain.Refund;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Repository
public class RefundRepository {

    private final ConcurrentHashMap<String, Refund> byRefundNo = new ConcurrentHashMap<>();
    /** outTradeNo → 退款单列表（支持一个支付单多次部分退款） */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Refund>> byTradeNo = new ConcurrentHashMap<>();

    /** 幂等写入：outRefundNo 已存在则不覆盖。返回 true 表示首次插入。 */
    public boolean saveIfAbsent(Refund refund) {
        boolean inserted = byRefundNo.putIfAbsent(refund.getOutRefundNo(), refund) == null;
        if (inserted) {
            byTradeNo.computeIfAbsent(refund.getOutTradeNo(), k -> new CopyOnWriteArrayList<>())
                     .add(refund);
        }
        return inserted;
    }

    public void save(Refund refund) {
        byRefundNo.put(refund.getOutRefundNo(), refund);
    }

    public Optional<Refund> findByOutRefundNo(String outRefundNo) {
        return Optional.ofNullable(byRefundNo.get(outRefundNo));
    }

    public List<Refund> findByOutTradeNo(String outTradeNo) {
        CopyOnWriteArrayList<Refund> list = byTradeNo.get(outTradeNo);
        return list == null ? List.of() : List.copyOf(list);
    }
}
