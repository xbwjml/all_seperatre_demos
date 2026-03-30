package com.example.demo.UserTaskFairScheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 启动时自动运行的演示：
 *
 * 场景：
 *   - alice  提交 10 个任务（"大用户"）
 *   - bob    提交  2 个任务（"小用户"）
 *   - carol  提交  1 个任务（"偶发用户"）
 *
 * 预期结果（WFQ 公平调度）：
 *   调度顺序大致为 alice→bob→carol→alice→bob→alice→alice...
 *   bob 和 carol 的任务不会被 alice 的大量任务饿死，
 *   它们会在早期轮次中被优先调度。
 */
@Component
public class FairSchedulingDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FairSchedulingDemo.class);

    private final FairTaskScheduler scheduler;

    public FairSchedulingDemo(FairTaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("========== [Demo] 公平调度演示开始 ==========");
        log.info("[Demo] 场景: alice 提交 10 个任务, bob 提交 2 个任务, carol 提交 1 个任务");
        log.info("[Demo] 观察日志中的执行顺序，bob/carol 不应被 alice 饿死");

        // alice 一次性提交 10 个任务
        for (int i = 1; i <= 10; i++) {
            scheduler.submitTask(new Task("alice", "alice-job-" + i));
        }

        // bob 提交 2 个任务
        scheduler.submitTask(new Task("bob", "bob-job-1"));
        scheduler.submitTask(new Task("bob", "bob-job-2"));

        // carol 提交 1 个任务
        scheduler.submitTask(new Task("carol", "carol-job-1"));

        // 等待所有任务执行完成，期间每隔 2 秒打印一次统计
        for (int i = 0; i < 8; i++) {
            TimeUnit.SECONDS.sleep(2);
            log.info("[Demo] 当前统计: {}", scheduler.allStats());
        }

        log.info("========== [Demo] 演示结束 ==========");
    }
}
