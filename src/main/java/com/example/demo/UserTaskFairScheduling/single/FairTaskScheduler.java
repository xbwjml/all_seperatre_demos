package com.example.demo.UserTaskFairScheduling.single;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
 *
 * 内存安全（三项修复）：
 * A. 每用户队列设置上限（UserQueue.MAX_SIZE），超限时 submitTask 返回 false（背压）。
 * B. workerPool 使用有界 ArrayBlockingQueue，队列满时阻塞调度线程（背压）。
 * C. 定时清理 userQueues 中队列已空的条目，防止长期运行后内存持续增长。
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
        this.readyQueue = new PriorityQueue<>();
        this.userQueues = new ConcurrentHashMap<>();
        this.lock       = new ReentrantLock();
        this.notEmpty   = lock.newCondition();

        // 修复B：workerPool 使用有界队列 + 阻塞式拒绝策略（背压）
        // 当等待队列满时，拒绝处理器将任务重新 put() 回队列，
        // 阻塞调度线程直到有空位，而不是无限堆积 Runnable 对象。
        int workers       = Runtime.getRuntime().availableProcessors();
        int queueCapacity = workers * 4;
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "fair-worker");
            t.setDaemon(true);
            return t;
        };
        this.workerPool = new ThreadPoolExecutor(
                workers, workers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                (runnable, executor) -> {
                    try {
                        executor.getQueue().put(runnable);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
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
        log.info("[FairScheduler] 启动完成，工作线程数={}, workerPool队列上限={}",
                workers, queueCapacity);
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * 提交任务
     *
     * 修复A：调用 UserQueue.enqueue() 时检查队列上限，超限返回 false。
     *
     * @param task 任务对象（userId 标识提交者）
     * @return true=入队成功；false=该用户队列已满，调用方应返回 429
     */
    public boolean submitTask(Task task) {
        lock.lock();
        try {
            UserQueue uq = userQueues.computeIfAbsent(task.getUserId(), uid -> {
                long initVt = currentMinVirtualTime();
                UserQueue newUq = new UserQueue(uid, initVt);
                log.info("[FairScheduler] 新用户加入: userId={}, initVt={}", uid, initVt);
                return newUq;
            });

            // 修复A：队列满时拒绝入队
            boolean wasEmpty = uq.isEmpty();
            if (!uq.enqueue(task)) {
                log.warn("[FairScheduler] 队列已满，拒绝入队: userId={}, size={}/{}",
                        task.getUserId(), uq.size(), UserQueue.MAX_SIZE);
                return false;
            }
            log.debug("[FairScheduler] 任务入队: {}", task);

            if (wasEmpty) {
                readyQueue.offer(uq);
            }
            notEmpty.signal();
            return true;
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

    /**
     * 修复C：定时清理 userQueues 中队列已空的用户条目，防止长期运行后内存持续增长。
     *
     * userQueues 持有所有曾经提交过任务的用户的 UserQueue 对象。
     * 任务执行完毕后，UserQueue 本身并不会自动从 Map 中移除。
     * 此方法每 5 分钟扫描一次，移除 pending==0 的条目，释放 UserQueue 及其内部对象。
     *
     * 下次该用户再提交任务时，会在 submitTask 中重新创建 UserQueue，
     * 初始 vt = 当前全局最小 vt，仍然保证公平性。
     */
    @Scheduled(fixedDelay = 300_000)
    public void cleanupInactiveUserQueues() {
        lock.lock();
        try {
            int before = userQueues.size();
            userQueues.entrySet().removeIf(e -> e.getValue().isEmpty());
            int removed = before - userQueues.size();
            if (removed > 0) {
                log.info("[FairScheduler] 清理了 {} 个空队列用户，剩余 {} 个", removed, userQueues.size());
            }
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        schedulerThread.interrupt();
        workerPool.shutdown();
        log.info("[FairScheduler] 已关闭");
    }
}
