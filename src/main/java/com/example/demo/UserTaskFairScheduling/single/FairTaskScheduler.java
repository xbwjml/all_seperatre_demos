package com.example.demo.UserTaskFairScheduling.single;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 加权公平队列调度器（Weighted Fair Queuing）
 *
 * 设计要点：
 * 1. 每个用户维护一个独立的 UserQueue，持有该用户的虚拟时间（virtualTime）。
 * 2. 外部提交任务时通过 submitTask() 入队；调度线程从优先队列中选取
 *    virtualTime 最小的用户，取其队头任务执行，执行后累加 virtualTime。
 * 3. 新用户加入时，其初始 virtualTime = 当前所有用户的最小 virtualTime，
 *    避免新用户因 vt=0 而独占调度资源。
 * 4. 用户队列变为空时，从优先队列移除；下次提交任务时重新加入。
 * 5. 任务实际执行逻辑由外部通过 taskHandler 注入，保持调度器与业务解耦。
 */
@Component
public class FairTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(FairTaskScheduler.class);

    /** 按虚拟时间排序的优先队列，持有"有待执行任务"的用户队列 */
    private final PriorityQueue<UserQueue> readyQueue;

    /** userId -> UserQueue 的全量映射（含空队列用户，用于快速定位） */
    private final Map<String, UserQueue> userQueues;

    /** 保护 readyQueue 与 userQueues 的并发锁 */
    private final ReentrantLock lock;

    /** 有新任务到来时唤醒调度线程 */
    private final Condition notEmpty;

    /** 任务执行线程池 */
    private final ExecutorService workerPool;

    /** 调度线程 */
    private final Thread schedulerThread;

    /** 任务执行逻辑（业务方注入，默认模拟 sleep） */
    private Consumer<Task> taskHandler;

    private volatile boolean running = true;

    public FairTaskScheduler() {
        this.readyQueue  = new PriorityQueue<>();
        this.userQueues  = new ConcurrentHashMap<>();
        this.lock        = new ReentrantLock();
        this.notEmpty    = lock.newCondition();
        this.workerPool  = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                r -> {
                    Thread t = new Thread(r, "fair-worker");
                    t.setDaemon(true);
                    return t;
                }
        );
        // 默认 handler：模拟任务耗时（100ms * cost）
        this.taskHandler = task -> {
            try {
                Thread.sleep(100L * task.getCost());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        this.schedulerThread = new Thread(this::schedulerLoop, "fair-scheduler");
        this.schedulerThread.setDaemon(true);
        this.schedulerThread.start();
        log.info("[FairScheduler] 启动完成，工作线程数={}", Runtime.getRuntime().availableProcessors());
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * 提交任务
     *
     * @param task 任务对象（userId 标识提交者）
     */
    public void submitTask(Task task) {
        lock.lock();
        try {
            UserQueue uq = userQueues.computeIfAbsent(task.getUserId(), uid -> {
                long initVt = currentMinVirtualTime();
                UserQueue newUq = new UserQueue(uid, initVt);
                log.info("[FairScheduler] 新用户加入: userId={}, initVt={}", uid, initVt);
                return newUq;
            });

            boolean wasEmpty = uq.isEmpty();
            uq.enqueue(task);
            log.debug("[FairScheduler] 任务入队: {}", task);

            // 若该用户队列之前为空（不在 readyQueue 中），现在重新加入
            if (wasEmpty) {
                readyQueue.offer(uq);
            }
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查询用户当前待执行任务数和虚拟时间
     */
    public Map<String, Object> userStats(String userId) {
        UserQueue uq = userQueues.get(userId);
        if (uq == null) {
            return Map.of("userId", userId, "pending", 0, "virtualTime", 0);
        }
        return Map.of("userId", userId, "pending", uq.size(), "virtualTime", uq.getVirtualTime());
    }

    /**
     * 所有用户的统计快照
     */
    public List<Map<String, Object>> allStats() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserQueue uq : userQueues.values()) {
            list.add(Map.of(
                    "userId",      uq.getUserId(),
                    "pending",     uq.size(),
                    "virtualTime", uq.getVirtualTime()
            ));
        }
        list.sort(Comparator.comparingLong(m -> (long) m.get("virtualTime")));
        return list;
    }

    /**
     * 替换任务执行逻辑（可选，方便测试或集成真实业务）
     */
    public void setTaskHandler(Consumer<Task> handler) {
        this.taskHandler = handler;
    }

    // ----------------------------------------------------------------
    //  Scheduler Loop (单线程，负责从优先队列取任务派发给 workerPool)
    // ----------------------------------------------------------------

    private void schedulerLoop() {
        while (running) {
            Task task = nextTask();
            if (task == null) continue;

            final Task finalTask = task;
            workerPool.submit(() -> {
                finalTask.markRunning();
                log.info("[FairScheduler] 开始执行: {}", finalTask);
                try {
                    taskHandler.accept(finalTask);
                    finalTask.markDone();
                    log.info("[FairScheduler] 执行完成: {}", finalTask);
                } catch (Exception e) {
                    finalTask.markFailed();
                    log.error("[FairScheduler] 执行失败: {}", finalTask, e);
                }
            });
        }
    }

    /**
     * 从 readyQueue 中选取 virtualTime 最小的用户，取其队头任务。
     * 若取完后该用户队列仍有任务，更新虚拟时间后重新入优先队列。
     */
    private Task nextTask() {
        lock.lock();
        try {
            // 等待直到有任务可取
            while (readyQueue.isEmpty()) {
                notEmpty.await(500, TimeUnit.MILLISECONDS);
                if (!running) return null;
            }

            // 取出 vt 最小的用户队列（移出优先队列，稍后按需重新插入）
            UserQueue uq = readyQueue.poll();
            if (uq == null) return null;

            Task task = uq.poll();
            if (task == null) return null;          // 理论上不会发生

            // 累加虚拟时间
            uq.addVirtualTime(task.getCost());

            // 若该用户还有待执行的任务，重新加入优先队列
            if (!uq.isEmpty()) {
                readyQueue.offer(uq);
            }

            return task;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    /**
     * 当前所有用户中最小的虚拟时间（用于初始化新用户的 vt，防止新用户独占调度）
     */
    private long currentMinVirtualTime() {
        return userQueues.values().stream()
                .mapToLong(UserQueue::getVirtualTime)
                .min()
                .orElse(0L);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        schedulerThread.interrupt();
        workerPool.shutdown();
        log.info("[FairScheduler] 已关闭");
    }
}
