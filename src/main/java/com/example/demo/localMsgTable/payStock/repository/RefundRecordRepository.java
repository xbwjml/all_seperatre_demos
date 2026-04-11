package com.example.demo.localMsgTable.payStock.repository;

import com.example.demo.localMsgTable.payStock.domain.RefundRecord;
import com.example.demo.localMsgTable.payStock.enums.RefundStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class RefundRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefundRecordRepository(@Qualifier("payStockJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<RefundRecord> rowMapper = (rs, rowNum) -> RefundRecord.builder()
            .refundId(rs.getString("refund_id"))
            .orderId(rs.getString("order_id"))
            .amount(rs.getBigDecimal("amount"))
            .status(RefundStatus.valueOf(rs.getString("status")))
            .channelRefundNo(rs.getString("channel_refund_no"))
            .build();

    public void insert(RefundRecord record) {
        jdbcTemplate.update(
                "INSERT INTO refund_record (refund_id, order_id, amount, status, channel_refund_no) VALUES (?,?,?,?,?)",
                record.getRefundId(), record.getOrderId(), record.getAmount(),
                record.getStatus().name(), record.getChannelRefundNo());
    }

    public Optional<RefundRecord> findById(String refundId) {
        var list = jdbcTemplate.query("SELECT * FROM refund_record WHERE refund_id = ?", rowMapper, refundId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }
}
