package com.example.demo.hiveCases.jobSync.service;

import com.example.demo.hiveCases.jobSync.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Hive 写入服务。
 *
 * <p>通过 HiveServer2 JDBC 将订单数据写入 Hive ODS 层分区表。
 * 每次同步前先执行 {@code ADD PARTITION}（幂等），再批量 INSERT。
 *
 * <p>对应 Hive 建表语句：
 * <pre>
 * CREATE DATABASE IF NOT EXISTS ods;
 *
 * CREATE EXTERNAL TABLE IF NOT EXISTS ods.orders (
 *     order_id     STRING,
 *     user_id      BIGINT,
 *     product_name STRING,
 *     amount       DECIMAL(10,2),
 *     status       STRING,
 *     create_time  STRING,
 *     update_time  STRING
 * )
 * PARTITIONED BY (dt STRING COMMENT '同步日期 yyyy-MM-dd')
 * STORED AS ORC
 * TBLPROPERTIES ('orc.compress'='SNAPPY');
 * </pre>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "demo.hive-sync.enabled", havingValue = "true")
public class HiveSyncService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Hive 目标库表，可按需改为配置项 */
    private static final String HIVE_TABLE = "ods.orders";

    private final JdbcTemplate hiveJdbcTemplate;

    public HiveSyncService(@Qualifier("hiveJdbcTemplate") JdbcTemplate hiveJdbcTemplate) {
        this.hiveJdbcTemplate = hiveJdbcTemplate;
    }

    /**
     * 将一批订单写入 Hive 指定日期分区。
     *
     * <p>策略：先删除当天分区（覆盖写，保证幂等），再批量 INSERT。
     *
     * @param orders 待写入订单列表
     * @param dt     目标分区日期
     */
    public void syncToHive(List<Order> orders, LocalDate dt) {
        if (orders.isEmpty()) {
            log.info("[HiveSync] 无数据需要同步，分区 dt={}", dt);
            return;
        }

        String partition = dt.format(DT_FMT);
        log.info("[HiveSync] 开始写入 Hive，分区 dt={}，共 {} 条", partition, orders.size());

        ensurePartition(partition);
        dropPartitionData(partition);
        batchInsert(orders, partition);

        log.info("[HiveSync] 写入完成，分区 dt={}，共 {} 条", partition, orders.size());
    }

    // -------------------------------------------------------------------------

    /**
     * 确保分区存在（IF NOT EXISTS 保证幂等）。
     * Hive 动态分区也可自动创建，此处显式创建更安全。
     */
    private void ensurePartition(String partition) {
        String sql = String.format(
                "ALTER TABLE %s ADD IF NOT EXISTS PARTITION (dt='%s')",
                HIVE_TABLE, partition);
        hiveJdbcTemplate.execute(sql);
        log.debug("[HiveSync] 分区已就绪：dt={}", partition);
    }

    /**
     * 删除分区内旧数据，实现覆盖写（幂等重跑）。
     * 生产环境若需保留历史快照，可去掉此步改为追加写。
     */
    private void dropPartitionData(String partition) {
        String sql = String.format(
                "ALTER TABLE %s DROP IF EXISTS PARTITION (dt='%s')",
                HIVE_TABLE, partition);
        hiveJdbcTemplate.execute(sql);
        // 删后重新创建空分区，后续 INSERT 才能正常写入
        ensurePartition(partition);
        log.debug("[HiveSync] 旧分区数据已清理：dt={}", partition);
    }

    /**
     * 批量 INSERT INTO Hive 分区。
     *
     * <p>Hive JDBC 不支持 JDBC batch，逐条拼 SQL 后一次性提交；
     * 数据量极大时建议改为先写 HDFS 文件再 LOAD DATA。
     */
    private void batchInsert(List<Order> orders, String partition) {
        for (Order o : orders) {
            String sql = String.format(
                    "INSERT INTO TABLE %s PARTITION (dt='%s') VALUES ('%s', %d, '%s', %s, '%s', '%s', '%s')",
                    HIVE_TABLE,
                    partition,
                    escape(o.getOrderId()),
                    o.getUserId(),
                    escape(o.getProductName()),
                    o.getAmount().toPlainString(),
                    escape(o.getStatus()),
                    o.getCreateTime() != null ? o.getCreateTime().format(TS_FMT) : "",
                    o.getUpdateTime() != null ? o.getUpdateTime().format(TS_FMT) : ""
            );
            hiveJdbcTemplate.execute(sql);
        }
    }

    /** 简单转义单引号，防止 SQL 注入（生产环境建议用 PreparedStatement）。 */
    private String escape(String value) {
        if (value == null) return "";
        return value.replace("'", "\\'");
    }
}
