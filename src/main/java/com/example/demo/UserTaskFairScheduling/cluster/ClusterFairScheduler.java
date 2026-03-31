package com.example.demo.UserTaskFairScheduling.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 基于 Redis 的集群公平调度器（Weighted Fair Queuing）
 *
 * ┌─────────────────────────────────────────────────────────┐
 * │  Redis 数据结构                                          │
 * │                                                          │
 * │  fair:cluster:vt                Sorted Set              │
 * │    member = userId, score = virtualTime                  │
 * │    → 多实例共享，代表每个用户已消耗的"调度份额"           │
 * │                                                          │
 * │  fair:cluster:queue:{userId}    List (FIFO)              │
 * │    element = ClusterTask JSON                            │
 * │    → RPUSH 入队，LPOP 出队                               │
 * │                                                          │
 * │  fair:cluster:sched:lock        String                   │
 * │    → 分布式锁，防止多实例同时调度同一任务                 │
 * └─────────────────────────────────────────────────────────┘
 *
 * 调度循环（每个实例各自运行）：
 *   1. 尝试获取分布式锁（SET NX EX）
 *   2. 从 vt Sorted Set 取出 score 最小的 userId
 *   3. LPOP 该用户的任务队列
 *   4. ZINCRBY vt += task.cost（更新虚拟时间）
 *   5. 若队列已空则 ZREM（移出调度池）
 *   6. 释放锁，在锁外异步执行任务
 *
 * 公平性保证：
 *   - 无论任务提交到哪个实例，vt 始终存储在 Redis，全局共享
 *   - 新用户初始 vt = 当前全局最小 vt（防止新用户独占调度资源）
 *
 * 内存安全保证（三项修复）：
 *   1. 每用户 Redis List 设置上限（MAX_USER_QUEUE_SIZE），超限拒绝接入（背压）
 *   2. workerPool 使用有界 ArrayBlockingQueue，拒绝时阻塞调度线程（背压）
 *   3. 定时任务清理 vt Sorted Set 中队列已空的僵尸成员，防止 Redis 内存持续增长
 */
@Component
public class ClusterFairScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClusterFairScheduler.class);

    // ---------- Redis Key 常量 ----------
    static final String VT_KEY       = "fair:cluster:vt";
    static final String QUEUE_PREFIX = "fair:cluster:queue:";
    static final String LOCK_KEY     = "fair:cluster:sched:lock";

    /** 锁的持有标识（每个实例唯一），用于安全释放 */
    private final String lockValue = UUID.randomUUID().toString();

    /** 锁超时时间（单位：秒）—— 应大于单次调度操作耗时，小于任务执行时间 */
    private static final long LOCK_TTL_SECONDS = 3L;

    /**
     * 修复1：每用户 Redis List 上限。
     * 超过此值时 submitTask 返回 false，调用方应向用户返回 429。
     */
    static final long MAX_USER_QUEUE_SIZE = 500;

    /**
     * 修复2：workerPool 内部有界队列容量 = 线程数 × 4。
     * 队列满时拒绝策略会阻塞调度线程，形成背压，
     * 防止调度线程将大量 ClusterTask 对象堆积在 JVM 堆上。
     */
    private static final int WORKER_QUEUE_MULTIPLIER = 4;

    /**
     * 安全释放锁的 Lua 脚本：只有持有者才能删除（防止误删其他实例的锁）
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ExecutorService workerPool;
    private final Thread schedulerThread;

    /** 任务执行逻辑（业务方注入，默认模拟 sleep） */
    private Consumer<ClusterTask> taskHandler;

    private volatile boolean running = true;

    public ClusterFairScheduler(StringRedisTemplate redis) {
        this.redis  = redis;
        this.mapper = new ObjectMapper();

        int workers       = Runtime.getRuntime().availableProcessors();
        int queueCapacity = workers * WORKER_QUEUE_MULTIPLIER;
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "cluster-worker");
            t.setDaemon(true);
            return t;
        };
        // 修复2：有界队列 + 阻塞式拒绝策略（背压）
        // 当 workerPool 的等待队列满时，RejectedExecutionHandler 将任务重新放回队列，
        // 调度线程被阻塞直到有空位，而不是无限堆积 Runnable 对象。
        this.workerPool = new ThreadPoolExecutor(
                workers, workers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                (runnable, executor) -> {
                    try {
                        // put() 阻塞直到队列有空位，形成背压
                        executor.getQueue().put(runnable);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
        );

        // 默认 handler：模拟执行耗时
        this.taskHandler = task -> {
            try {
                Thread.sleep(100L * task.getCost());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        this.schedulerThread = new Thread(this::schedulerLoop, "cluster-scheduler");
        this.schedulerThread.setDaemon(true);
        this.schedulerThread.start();
        log.info("[ClusterScheduler] 启动, instanceId={}", lockValue.substring(0, 8));
    }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * 提交任务
     *   1. 修复1：检查该用户的 Redis List 长度，超出 MAX_USER_QUEUE_SIZE 则拒绝（返回 false）
     *   2. 序列化 task 为 JSON，RPUSH 到用户队列
     *   3. ZADD NX：若用户首次出现，以当前全局最小 vt 初始化（保证公平起跑线）
     *
     * @return true=入队成功；false=队列已满，调用方应向客户端返回 429
     */
    public boolean submitTask(ClusterTask task) {
        String queueKey = QUEUE_PREFIX + task.getUserId();

        // 修复1：软限流——检查队列深度，超限直接拒绝（背压到调用方）
        Long queueLen = redis.opsForList().size(queueKey);
        if (queueLen != null && queueLen >= MAX_USER_QUEUE_SIZE) {
            log.warn("[ClusterScheduler] 队列已满，拒绝入队: userId={}, size={}/{}",
                    task.getUserId(), queueLen, MAX_USER_QUEUE_SIZE);
            return false;
        }

        String json = serialize(task);
        if (json == null) return false;

        redis.opsForList().rightPush(queueKey, json);

        double initVt = currentMinVirtualTime();
        redis.opsForZSet().addIfAbsent(VT_KEY, task.getUserId(), initVt);

        log.debug("[ClusterScheduler] 任务入队: {}", task);
        return true;
    }

    /**
     * 所有用户的统计快照（virtualTime + pending 数量）
     */
    public List<Map<String, Object>> allStats() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(VT_KEY, 0, -1);

        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples == null) return result;

        for (ZSetOperations.TypedTuple<String> t : tuples) {
            String userId = t.getValue();
            double vt     = t.getScore() != null ? t.getScore() : 0.0;
            Long   pending = redis.opsForList().size(QUEUE_PREFIX + userId);
            result.add(Map.of(
                    "userId",      userId,
                    "virtualTime", (long) vt,
                    "pending",     pending != null ? pending : 0L
            ));
        }
        return result;
    }

    /**
     * 指定用户统计
     */
    public Map<String, Object> userStats(String userId) {
        Double vt      = redis.opsForZSet().score(VT_KEY, userId);
        Long   pending = redis.opsForList().size(QUEUE_PREFIX + userId);
        return Map.of(
                "userId",      userId,
                "virtualTime", vt != null ? vt.longValue() : 0L,
                "pending",     pending != null ? pending : 0L
        );
    }

    /**
     * 替换任务执行逻辑（可选）
     */
    public void setTaskHandler(Consumer<ClusterTask> handler) {
        this.taskHandler = handler;
    }

    // ----------------------------------------------------------------
    //  Scheduler Loop
    // ----------------------------------------------------------------

    private void schedulerLoop() {
        while (running) {
            try {
                ClusterTask task = tryPickNextTask();
                if (task == null) {
                    // 没拿到锁或无任务，短暂休眠后重试
                    TimeUnit.MILLISECONDS.sleep(50);
                    continue;
                }
                final ClusterTask finalTask = task;
                workerPool.submit(() -> executeTask(finalTask));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[ClusterScheduler] 调度异常", e);
            }
        }
    }

    /**
     * 尝试从 Redis 中取出下一个待执行任务（需先获取分布式锁）
     *
     * @return 取到的任务，或 null（未抢到锁 / 当前无任务）
     */
    private ClusterTask tryPickNextTask() {
        // 1. 尝试获取分布式锁
        Boolean locked = redis.opsForValue()
                .setIfAbsent(LOCK_KEY, lockValue, Duration.ofSeconds(LOCK_TTL_SECONDS));
        if (!Boolean.TRUE.equals(locked)) {
            return null;  // 其他实例持有锁，本轮放弃
        }

        try {
            // 2. 取 vt 最小的用户
            Set<ZSetOperations.TypedTuple<String>> top =
                    redis.opsForZSet().rangeWithScores(VT_KEY, 0, 0);
            if (top == null || top.isEmpty()) {
                return null;  // 当前没有任何用户在调度池中
            }

            ZSetOperations.TypedTuple<String> entry = top.iterator().next();
            String userId = entry.getValue();
            double curVt  = entry.getScore() != null ? entry.getScore() : 0.0;

            // 3. 弹出该用户队列的队头任务
            String json = redis.opsForList().leftPop(QUEUE_PREFIX + userId);
            if (json == null) {
                // 用户队列已空（任务刚被其他实例消费），清出调度池
                redis.opsForZSet().remove(VT_KEY, userId);
                return null;
            }

            ClusterTask task = deserialize(json);
            if (task == null) return null;

            // 4. 更新虚拟时间
            redis.opsForZSet().incrementScore(VT_KEY, userId, task.getCost());

            // 5. 若队列已空，从调度池移除（下次提交时会重新加入）
            Long remaining = redis.opsForList().size(QUEUE_PREFIX + userId);
            if (remaining == null || remaining == 0) {
                redis.opsForZSet().remove(VT_KEY, userId);
            }

            log.debug("[ClusterScheduler] 取到任务: {}, vt: {} -> {}",
                    task, (long) curVt, (long) (curVt + task.getCost()));
            return task;

        } finally {
            // 6. 安全释放锁（Lua 保证只有持有者才能删除）
            releaseLock();
        }
    }

    private void executeTask(ClusterTask task) {
        task.markRunning();
        log.info("[ClusterScheduler] 开始执行: {}", task);
        try {
            taskHandler.accept(task);
            task.markDone();
            log.info("[ClusterScheduler] 执行完成: {}", task);
        } catch (Exception e) {
            task.markFailed();
            log.error("[ClusterScheduler] 执行失败: {}", task, e);
        }
    }

    // ----------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------

    private double currentMinVirtualTime() {
        Set<ZSetOperations.TypedTuple<String>> top =
                redis.opsForZSet().rangeWithScores(VT_KEY, 0, 0);
        if (top == null || top.isEmpty()) return 0.0;
        ZSetOperations.TypedTuple<String> entry = top.iterator().next();
        return entry.getScore() != null ? entry.getScore() : 0.0;
    }

    private void releaseLock() {
        redis.execute(RELEASE_LOCK_SCRIPT,
                Collections.singletonList(LOCK_KEY),
                lockValue);
    }

    private String serialize(ClusterTask task) {
        try {
            return mapper.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            log.error("[ClusterScheduler] 序列化失败: {}", task, e);
            return null;
        }
    }

    private ClusterTask deserialize(String json) {
        try {
            return mapper.readValue(json, ClusterTask.class);
        } catch (JsonProcessingException e) {
            log.error("[ClusterScheduler] 反序列化失败: {}", json, e);
            return null;
        }
    }

    /**
     * 修复3：定时清理 vt Sorted Set 中的"僵尸成员"。
     *
     * 正常情况下，队列变空时 tryPickNextTask() 会执行 ZREM 将用户从调度池移除。
     * 但若实例在 LPOP 之后、ZREM 之前崩溃，userId 会残留在 vt 中，
     * 对应的 Redis List 却已经为空（或不存在）。
     * 此任务每 60 秒扫描一次，清理这类僵尸条目，防止 Redis 内存持续增长。
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupStaleVtEntries() {
        Set<String> members = redis.opsForZSet().range(VT_KEY, 0, -1);
        if (members == null || members.isEmpty()) return;

        int removed = 0;
        for (String userId : members) {
            Long size = redis.opsForList().size(QUEUE_PREFIX + userId);
            if (size == null || size == 0) {
                redis.opsForZSet().remove(VT_KEY, userId);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[ClusterScheduler] 清理了 {} 个僵尸 vt 条目", removed);
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        schedulerThread.interrupt();
        workerPool.shutdown();
        log.info("[ClusterScheduler] 已关闭");
    }
}
