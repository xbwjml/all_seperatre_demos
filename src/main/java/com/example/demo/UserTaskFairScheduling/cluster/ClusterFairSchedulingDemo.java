package com.example.demo.UserTaskFairScheduling.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 集群版演示入口（在单机版之后运行，Order=2）
 *
 * 场景与单机版相同：
 *   - alice  并发提交 10 个任务（"大用户"）
 *   - bob    并发提交  2 个任务（"小用户"）
 *   - carol  并发提交  1 个任务（"偶发用户"）
 *
 * 三个用户各自在独立线程中并发调用 ClusterTaskController，
 * 模拟多用户并发 HTTP 请求（此处直接调用 Controller 方法，效果等同）。
 *
 * 关键观察点：
 *   - 无论这些任务落到哪个实例，Redis 中的 vt 始终是全局一致的
 *   - GET /cluster/fair/stats 在任意实例上查询，数据均相同
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "demo.cluster-fair-scheduling.enabled", havingValue = "true")
public class ClusterFairSchedulingDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClusterFairSchedulingDemo.class);

    private final ClusterTaskController controller;
    private final ClusterFairScheduler  scheduler;
    private final StringRedisTemplate   redis;

    public ClusterFairSchedulingDemo(ClusterTaskController controller,
                                     ClusterFairScheduler scheduler,
                                     StringRedisTemplate redis) {
        this.controller = controller;
        this.scheduler  = scheduler;
        this.redis      = redis;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("========== [ClusterDemo] 集群公平调度演示开始 ==========");

        // 清理上次运行留下的 Redis 数据，确保演示干净
        cleanupRedis();

        ExecutorService clientPool = Executors.newFixedThreadPool(3,
                r -> new Thread(r, "cluster-demo-client"));

        // alice：并发调用 batch 接口，一次性提交 10 个任务
        CompletableFuture<Void> aliceFuture = CompletableFuture.runAsync(() -> {
            log.info("[ClusterDemo][alice] 批量提交 10 个任务 -> ClusterTaskController");
            controller.batchSubmit(new ClusterTaskController.BatchRequest("alice", 10, 1));
            log.info("[ClusterDemo][alice] 提交完成");
        }, clientPool);

        // bob：并发逐一提交 2 个任务
        CompletableFuture<Void> bobFuture = CompletableFuture.runAsync(() -> {
            log.info("[ClusterDemo][bob] 提交 2 个任务 -> ClusterTaskController");
            for (int i = 1; i <= 2; i++) {
                controller.submit(new ClusterTaskController.SubmitRequest("bob", "bob-job-" + i, 1));
            }
            log.info("[ClusterDemo][bob] 提交完成");
        }, clientPool);

        // carol：并发提交 1 个任务
        CompletableFuture<Void> carolFuture = CompletableFuture.runAsync(() -> {
            log.info("[ClusterDemo][carol] 提交 1 个任务 -> ClusterTaskController");
            controller.submit(new ClusterTaskController.SubmitRequest("carol", "carol-job-1", 1));
            log.info("[ClusterDemo][carol] 提交完成");
        }, clientPool);

        // 等待三个用户全部提交完毕
        CompletableFuture.allOf(aliceFuture, bobFuture, carolFuture).join();
        log.info("[ClusterDemo] 所有用户提交完毕，观察调度顺序（数据来自 Redis，全局视图）...");
        clientPool.shutdown();

        // 每隔 2 秒打印全局统计，所有任务完成后提前退出
        for (int i = 0; i < 8; i++) {
            TimeUnit.SECONDS.sleep(2);
            List<Map<String, Object>> stats = scheduler.allStats();
            log.info("[ClusterDemo] 全局统计（来自 Redis）: {}", stats);
            if (stats.stream().allMatch(m -> ((Number) m.get("pending")).longValue() == 0)) {
                log.info("[ClusterDemo] 所有任务执行完毕");
                break;
            }
        }

        log.info("========== [ClusterDemo] 演示结束 ==========");
    }

    /**
     * 清理本次演示相关的 Redis Key，避免上次运行残留数据干扰
     */
    private void cleanupRedis() {
        redis.delete(ClusterFairScheduler.VT_KEY);
        redis.delete(ClusterFairScheduler.LOCK_KEY);
        // 清理所有用户队列（扫描 fair:cluster:queue:* 前缀）
        Set<String> queueKeys = redis.keys(ClusterFairScheduler.QUEUE_PREFIX + "*");
        if (queueKeys != null && !queueKeys.isEmpty()) {
            redis.delete(queueKeys);
        }
        log.info("[ClusterDemo] Redis 数据已清理");
    }
}
