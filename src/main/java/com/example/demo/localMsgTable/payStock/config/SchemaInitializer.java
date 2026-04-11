package com.example.demo.localMsgTable.payStock.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class SchemaInitializer {

    private final JdbcTemplate jdbc;

    public SchemaInitializer(@Qualifier("payStockJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initSchema() {
        log.info("[PayStock] Initializing database schema...");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pay_order (
                    order_id        VARCHAR(64)    PRIMARY KEY,
                    sku_id          VARCHAR(64)    NOT NULL,
                    quantity        INT            NOT NULL,
                    amount          DECIMAL(12,2)  NOT NULL,
                    channel         VARCHAR(32)    NOT NULL,
                    status          VARCHAR(32)    NOT NULL,
                    channel_trade_no VARCHAR(128)  DEFAULT NULL
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS local_message (
                    id              VARCHAR(64)    PRIMARY KEY,
                    order_id        VARCHAR(64)    NOT NULL,
                    msg_type        VARCHAR(32)    NOT NULL,
                    payload         TEXT           NOT NULL,
                    status          VARCHAR(16)    NOT NULL,
                    retry_count     INT            NOT NULL DEFAULT 0,
                    max_retry       INT            NOT NULL DEFAULT 5,
                    next_retry_time DATETIME       NOT NULL,
                    INDEX idx_status_retry (status, next_retry_time)
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS refund_record (
                    refund_id        VARCHAR(64)    PRIMARY KEY,
                    order_id         VARCHAR(64)    NOT NULL,
                    amount           DECIMAL(12,2)  NOT NULL,
                    status           VARCHAR(32)    NOT NULL,
                    channel_refund_no VARCHAR(128)  DEFAULT NULL,
                    INDEX idx_order_id (order_id)
                )
                """);

        log.info("[PayStock] Database schema initialized.");
    }
}
