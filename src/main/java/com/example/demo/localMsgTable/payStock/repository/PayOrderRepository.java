package com.example.demo.localMsgTable.payStock.repository;

import com.example.demo.localMsgTable.payStock.domain.PayOrder;
import com.example.demo.localMsgTable.payStock.enums.OrderStatus;
import com.example.demo.localMsgTable.payStock.enums.PayChannel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class PayOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public PayOrderRepository(@Qualifier("payStockJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<PayOrder> rowMapper = (rs, rowNum) -> PayOrder.builder()
            .orderId(rs.getString("order_id"))
            .skuId(rs.getString("sku_id"))
            .quantity(rs.getInt("quantity"))
            .amount(rs.getBigDecimal("amount"))
            .channel(PayChannel.valueOf(rs.getString("channel")))
            .status(OrderStatus.valueOf(rs.getString("status")))
            .channelTradeNo(rs.getString("channel_trade_no"))
            .build();

    public void insert(PayOrder order) {
        jdbcTemplate.update(
                "INSERT INTO pay_order (order_id, sku_id, quantity, amount, channel, status, channel_trade_no) VALUES (?,?,?,?,?,?,?)",
                order.getOrderId(), order.getSkuId(), order.getQuantity(),
                order.getAmount(), order.getChannel().name(),
                order.getStatus().name(), order.getChannelTradeNo());
    }

    public Optional<PayOrder> findById(String orderId) {
        var list = jdbcTemplate.query("SELECT * FROM pay_order WHERE order_id = ?", rowMapper, orderId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    public int updateStatus(String orderId, OrderStatus newStatus, OrderStatus expectedOldStatus) {
        return jdbcTemplate.update(
                "UPDATE pay_order SET status = ? WHERE order_id = ? AND status = ?",
                newStatus.name(), orderId, expectedOldStatus.name());
    }

    public int updateStatusAndTradeNo(String orderId, OrderStatus newStatus, OrderStatus expectedOldStatus, String channelTradeNo) {
        return jdbcTemplate.update(
                "UPDATE pay_order SET status = ?, channel_trade_no = ? WHERE order_id = ? AND status = ?",
                newStatus.name(), channelTradeNo, orderId, expectedOldStatus.name());
    }
}
