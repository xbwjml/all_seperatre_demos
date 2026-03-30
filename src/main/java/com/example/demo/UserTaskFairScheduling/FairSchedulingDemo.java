package com.example.demo.UserTaskFairScheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 启动时自动运行的演示：
 *
 * 场景：
 *   - alice  并发提交 10 个任务（"大用户"）
 *   - bob    并发提交  2 个任务（"小用户"）
 *   - carol  并发提交  1 个任务（"偶发用户"）
 *
 * 三个用户各自在独立线程中并发调用 TaskController，模拟真实的多用户并发请求。
 *
 * 预期结果（WFQ 公平调度）：
 *   调度顺序大致为 alice→bob→carol→alice→bob→alice→alice...
 *   bob 和 carol 的任务不会被 alice 的大量任务饿死。
 */
@Component
public class FairSchedulingDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FairSchedulingDemo.class);

    private final TaskController controller;
    private final FairTaskScheduler scheduler;

    public FairSchedulingDemo(TaskController controller, FairTaskScheduler scheduler) {
        this.controller = controller;
        this.scheduler  = scheduler;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("========== [Demo] 公平调度演示开始 ==========");
        log.info("[Demo] alice/bob/carol 并发调用 TaskController 提交任务");

        ExecutorService clientPool = Executors.newFixedThreadPool(3,
                r -> new Thread(r, "demo-client"));

        // alice：并发调用 batch 接口，一次性提交 10 个任务
        CompletableFuture<Void> aliceFuture = CompletableFuture.runAsync(() -> {
            log.info("[Demo][alice] 开始批量提交 10 个任务");
            controller.batchSubmit(new TaskController.BatchRequest("alice", 10, 1));
            log.info("[Demo][alice] 批量提交完成");
        }, clientPool);

        // bob：并发逐一调用 submit 接口，提交 2 个任务
        CompletableFuture<Void> bobFuture = CompletableFuture.runAsync(() -> {
            log.info("[Demo][bob] 开始提交 2 个任务");
            for (int i = 1; i <= 2; i++) {
                controller.submit(new TaskController.SubmitRequest("bob", "bob-job-" + i, 1));
            }
            log.info("[Demo][bob] 提交完成");
        }, clientPool);

        // carol：并发调用 submit 接口，提交 1 个任务
        CompletableFuture<Void> carolFuture = CompletableFuture.runAsync(() -> {
            log.info("[Demo][carol] 开始提交 1 个任务");
            controller.submit(new TaskController.SubmitRequest("carol", "carol-job-1", 1));
            log.info("[Demo][carol] 提交完成");
        }, clientPool);

        // 等待三个用户全部提交完毕
        CompletableFuture.allOf(aliceFuture, bobFuture, carolFuture).join();
        log.info("[Demo] 所有用户提交完毕，开始观察调度顺序...");

        clientPool.shutdown();
    }
}
