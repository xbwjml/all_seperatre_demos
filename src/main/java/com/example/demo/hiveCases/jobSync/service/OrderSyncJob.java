package com.example.demo.hiveCases.jobSync.service;

import com.example.demo.hiveCases.jobSync.domain.Order;
import com.example.demo.hiveCases.jobSync.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 定时批量同步任务：MySQL 订单 → Hive ODS 层。
 *
 * <h3>同步策略</h3>
 * <ul>
 *   <li><b>增量模式（默认）</b>：每次拉取上次同步时间之后有变更的订单，写入当天分区。
 *       适合高频调度（分钟级），避免全表扫描。</li>
 *   <li><b>全量模式</b>：拉取全部订单，覆盖写当天分区。
 *       适合每日凌晨首次全量初始化，通过配置 {@code demo.hive-sync.full-sync-cron} 触发。</li>
 * </ul>
 *
 * <h3>幂等保证</h3>
 * {@link HiveSyncService} 写入前会先删除再重建分区，重跑安全。
 *
 * <h3>配置示例</h3>
 * <pre>
 * demo:
 *   hive-sync:
 *     enabled: true
 *     # 增量同步：每小时整点执行
 *     incremental-sync-cron: "0 0 * * * *"
 *     # 全量同步：每天凌晨 01:00 执行
 *     full-sync-cron: "0 0 1 * * *"
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.hive-sync.enabled", havingValue = "true")
public class OrderSyncJob {

    private final OrderRepository orderRepository;
    private final HiveSyncService hiveSyncService;

    /**
     * 记录上次增量同步的截止时间。
     * 生产环境应持久化到数据库或 ZooKeeper，防止重启后丢失进度。
     */
    private final AtomicReference<LocalDateTime> lastSyncTime =
            new AtomicReference<>(LocalDateTime.now().minusDays(1));

    // -------------------------------------------------------------------------
    // 增量同步
    // -------------------------------------------------------------------------

    /**
     * 增量同步：拉取自上次同步以来有变更的订单，写入当天 Hive 分区。
     * Cron 表达式通过配置项控制，默认每小时整点执行。
     */
    @Scheduled(cron = "${demo.hive-sync.incremental-sync-cron:0 0 * * * *}")
    public void incrementalSync() {
        LocalDateTime upperBound = LocalDateTime.now();
        LocalDateTime lower = lastSyncTime.get();

        log.info("[HiveSync] 增量同步开始，时间窗口：({}, {}]", lower, upperBound);

        try {
            List<Order> orders = orderRepository.findUpdatedSince(lower, upperBound);
            log.info("[HiveSync] 从 MySQL 拉取到 {} 条变更订单", orders.size());

            hiveSyncService.syncToHive(orders, LocalDate.now());

            // 更新水位线，只有成功后才推进，保证 at-least-once 语义
            lastSyncTime.set(upperBound);
            log.info("[HiveSync] 增量同步完成，水位线推进至 {}", upperBound);

        } catch (Exception e) {
            // 失败不推进水位线，下次重跑会重新拉取同一窗口，保证数据不丢
            log.error("[HiveSync] 增量同步失败，水位线保持在 {}，下次重试", lower, e);
        }
    }

    // -------------------------------------------------------------------------
    // 全量同步
    // -------------------------------------------------------------------------

    /**
     * 全量同步：拉取 MySQL 全部订单，覆盖写当天 Hive 分区。
     * 适合每日凌晨初始化或数据修复。
     */
    @Scheduled(cron = "${demo.hive-sync.full-sync-cron:0 0 1 * * *}")
    public void fullSync() {
        log.info("[HiveSync] 全量同步开始");

        try {
            List<Order> orders = orderRepository.findAll();
            log.info("[HiveSync] 从 MySQL 全量拉取到 {} 条订单", orders.size());

            hiveSyncService.syncToHive(orders, LocalDate.now());

            // 全量同步后重置水位线，避免增量重复写入同一批数据
            lastSyncTime.set(LocalDateTime.now());
            log.info("[HiveSync] 全量同步完成");

        } catch (Exception e) {
            log.error("[HiveSync] 全量同步失败", e);
        }
    }
}
