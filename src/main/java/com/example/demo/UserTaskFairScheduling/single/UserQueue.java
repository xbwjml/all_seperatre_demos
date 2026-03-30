package com.example.demo.UserTaskFairScheduling.single;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 每个用户独立的任务队列，持有该用户的"虚拟时间"（WFQ 核心状态）。
 *
 * 虚拟时间语义：
 *   每次从该队列取出并执行一个任务后，virtualTime += task.getCost()
 *   调度器始终优先选取 virtualTime 最小的用户队列，
 *   从而确保长期公平：无论提交多少任务，每个用户占用的"调度份额"趋于相等。
 */
public class UserQueue implements Comparable<UserQueue> {

    private final String userId;
    private final LinkedBlockingQueue<Task> queue;

    /**
     * 虚拟时间：用 volatile 保证多线程可见，调度器单线程消费时也可不加 volatile，
     * 此处保留以支持未来的并发扩展。
     */
    private volatile long virtualTime;

    public UserQueue(String userId, long initialVirtualTime) {
        this.userId      = userId;
        this.queue       = new LinkedBlockingQueue<>();
        this.virtualTime = initialVirtualTime;
    }

    /** 提交任务入队 */
    public void enqueue(Task task) {
        queue.offer(task);
    }

    /** 取出队头任务，若队列为空返回 null */
    public Task poll() {
        return queue.poll();
    }

    /** 队列是否为空 */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /** 待执行任务数 */
    public int size() {
        return queue.size();
    }

    /** 执行完一个任务后累加虚拟时间 */
    public void addVirtualTime(long delta) {
        this.virtualTime += delta;
    }

    // ---------- getter ----------

    public String getUserId()      { return userId; }
    public long getVirtualTime()   { return virtualTime; }
    public void setVirtualTime(long vt) { this.virtualTime = vt; }

    /**
     * 优先队列排序依据：virtualTime 小的优先
     */
    @Override
    public int compareTo(UserQueue other) {
        return Long.compare(this.virtualTime, other.virtualTime);
    }

    @Override
    public String toString() {
        return String.format("UserQueue{userId=%s, vt=%d, pending=%d}",
                userId, virtualTime, queue.size());
    }
}
