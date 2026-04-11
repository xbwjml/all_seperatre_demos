package com.example.demo.localMsgTable.payStock.repository;

import com.example.demo.localMsgTable.payStock.domain.LocalMessage;
import com.example.demo.localMsgTable.payStock.enums.MsgStatus;
import com.example.demo.localMsgTable.payStock.enums.MsgType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class LocalMessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public LocalMessageRepository(@Qualifier("payStockJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<LocalMessage> rowMapper = (rs, rowNum) -> LocalMessage.builder()
            .id(rs.getString("id"))
            .orderId(rs.getString("order_id"))
            .msgType(MsgType.valueOf(rs.getString("msg_type")))
            .payload(rs.getString("payload"))
            .status(MsgStatus.valueOf(rs.getString("status")))
            .retryCount(rs.getInt("retry_count"))
            .maxRetry(rs.getInt("max_retry"))
            .nextRetryTime(rs.getTimestamp("next_retry_time").toLocalDateTime())
            .build();

    public void insert(LocalMessage msg) {
        jdbcTemplate.update(
                "INSERT INTO local_message (id, order_id, msg_type, payload, status, retry_count, max_retry, next_retry_time) VALUES (?,?,?,?,?,?,?,?)",
                msg.getId(), msg.getOrderId(), msg.getMsgType().name(),
                msg.getPayload(), msg.getStatus().name(),
                msg.getRetryCount(), msg.getMaxRetry(), msg.getNextRetryTime());
    }

    public int updateStatus(String id, MsgStatus newStatus) {
        return jdbcTemplate.update(
                "UPDATE local_message SET status = ? WHERE id = ?",
                newStatus.name(), id);
    }

    public int incrementRetryAndSetNextTime(String id, LocalDateTime nextRetryTime) {
        return jdbcTemplate.update(
                "UPDATE local_message SET retry_count = retry_count + 1, next_retry_time = ? WHERE id = ?",
                nextRetryTime, id);
    }

    public int markDead(String id) {
        return jdbcTemplate.update(
                "UPDATE local_message SET status = ? WHERE id = ?",
                MsgStatus.DEAD.name(), id);
    }

    public List<LocalMessage> findPendingMessages(LocalDateTime now, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM local_message WHERE status = ? AND next_retry_time <= ? ORDER BY next_retry_time ASC LIMIT ?",
                rowMapper, MsgStatus.PENDING.name(), now, limit);
    }
}
