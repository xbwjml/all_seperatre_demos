package com.example.demo.hiveCases.jobSync.repository;

import com.example.demo.hiveCases.jobSync.domain.Order;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 从 MySQL 查询订单数据，支持两种模式：
 * <ul>
 *   <li><b>全量</b>：拉取全部订单（首次初始化用）</li>
 *   <li><b>增量</b>：按 update_time 拉取指定时间窗口内变更的订单</li>
 * </ul>
 *
 * <p>对应 MySQL 建表语句：
 * <pre>
 * CREATE TABLE orders (
 *     order_id     VARCHAR(64)    NOT NULL PRIMARY KEY,
 *     user_id      BIGINT         NOT NULL,
 *     product_name VARCHAR(256)   NOT NULL,
 *     amount       DECIMAL(10,2)  NOT NULL,
 *     status       VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
 *     create_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     update_time  DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     INDEX idx_update_time (update_time)
 * );
 * </pre>
 */
@Repository
@ConditionalOnProperty(name = "demo.hive-sync.enabled", havingValue = "true")
public class OrderRepository {

    private static final String SQL_FULL =
            "SELECT order_id, user_id, product_name, amount, status, create_time, update_time " +
            "FROM orders ORDER BY update_time";

    private static final String SQL_INCREMENTAL =
            "SELECT order_id, user_id, product_name, amount, status, create_time, update_time " +
            "FROM orders WHERE update_time > ? AND update_time <= ? ORDER BY update_time";

    private final JdbcTemplate mysqlJdbcTemplate;

    public OrderRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
    }

    /** 全量查询所有订单。 */
    public List<Order> findAll() {
        return mysqlJdbcTemplate.query(SQL_FULL, new OrderRowMapper());
    }

    /**
     * 增量查询：拉取 (lastSyncTime, upperBound] 区间内 update_time 有变化的订单。
     *
     * @param lastSyncTime 上次同步截止时间（不含）
     * @param upperBound   本次同步截止时间（含），通常为当前时间
     */
    public List<Order> findUpdatedSince(LocalDateTime lastSyncTime, LocalDateTime upperBound) {
        return mysqlJdbcTemplate.query(
                SQL_INCREMENTAL,
                new OrderRowMapper(),
                Timestamp.valueOf(lastSyncTime),
                Timestamp.valueOf(upperBound)
        );
    }

    // -------------------------------------------------------------------------

    private static class OrderRowMapper implements RowMapper<Order> {
        @Override
        public Order mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp createTs = rs.getTimestamp("create_time");
            Timestamp updateTs = rs.getTimestamp("update_time");
            return Order.builder()
                    .orderId(rs.getString("order_id"))
                    .userId(rs.getLong("user_id"))
                    .productName(rs.getString("product_name"))
                    .amount(rs.getBigDecimal("amount"))
                    .status(rs.getString("status"))
                    .createTime(createTs != null ? createTs.toLocalDateTime() : null)
                    .updateTime(updateTs != null ? updateTs.toLocalDateTime() : null)
                    .build();
        }
    }
}
