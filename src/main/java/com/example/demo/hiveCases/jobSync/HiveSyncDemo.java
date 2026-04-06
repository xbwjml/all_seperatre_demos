package com.example.demo.hiveCases.jobSync;

import com.example.demo.hiveCases.jobSync.service.OrderSyncJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Hive 定时批量同步模块入口。
 *
 * <h3>启用方式</h3>
 * 在 {@code application.yml} 中开启：
 * <pre>
 * demo:
 *   hive-sync:
 *     enabled: true
 *     mysql:
 *       url: jdbc:mysql://localhost:3306/order_db?useSSL=false&serverTimezone=Asia/Shanghai
 *       username: root
 *       password: root
 *       driver-class-name: com.mysql.cj.jdbc.Driver
 *     hive:
 *       url: jdbc:hive2://localhost:10000/default
 *       username: hive
 *       password: ""
 *       driver-class-name: org.apache.hive.jdbc.HiveDriver
 *     # 增量同步 Cron（默认每小时整点）
 *     incremental-sync-cron: "0 0 * * * *"
 *     # 全量同步 Cron（默认每天凌晨 1 点）
 *     full-sync-cron: "0 0 1 * * *"
 *     # 启动后立即触发一次全量同步（用于演示/调试）
 *     run-on-startup: true
 * </pre>
 *
 * <h3>数据流</h3>
 * <pre>
 * MySQL orders 表
 *     │
 *     │  OrderRepository.findUpdatedSince() / findAll()
 *     ▼
 * List&lt;Order&gt;（内存）
 *     │
 *     │  HiveSyncService.syncToHive()
 *     ▼
 * Hive ods.orders PARTITION(dt='yyyy-MM-dd')
 * </pre>
 *
 * <h3>Hive 表结构（需提前建好）</h3>
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
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.hive-sync.enabled", havingValue = "true")
public class HiveSyncDemo {

    /**
     * 启动后立即执行一次全量同步（可通过 {@code demo.hive-sync.run-on-startup} 控制）。
     * 方便本地调试时无需等待 Cron 触发。
     */
    @Bean
    @ConditionalOnProperty(name = "demo.hive-sync.run-on-startup", havingValue = "true")
    public ApplicationRunner hiveSyncStartupRunner(OrderSyncJob syncJob) {
        return args -> {
            log.info("[HiveSyncDemo] 检测到 run-on-startup=true，启动后立即执行全量同步");
            syncJob.fullSync();
        };
    }
}
