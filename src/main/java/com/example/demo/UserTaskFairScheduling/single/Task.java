package com.example.demo.UserTaskFairScheduling.single;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务模型
 * cost 表示任务的"权重"，用于 WFQ 虚拟时间的累加
 */
public class Task {

    public enum Status {
        PENDING, RUNNING, DONE, FAILED
    }

    private final String taskId;
    private final String userId;
    private final String payload;
    private final int cost;                           // 任务权重（默认 1）
    private final LocalDateTime submitTime;
    private final AtomicReference<Status> status;
    private volatile LocalDateTime startTime;
    private volatile LocalDateTime finishTime;

    public Task(String userId, String payload, int cost) {
        this.taskId     = UUID.randomUUID().toString().substring(0, 8);
        this.userId     = userId;
        this.payload    = payload;
        this.cost       = cost;
        this.submitTime = LocalDateTime.now();
        this.status     = new AtomicReference<>(Status.PENDING);
    }

    public Task(String userId, String payload) {
        this(userId, payload, 1);
    }

    // ---------- getter ----------

    public String getTaskId()           { return taskId; }
    public String getUserId()           { return userId; }
    public String getPayload()          { return payload; }
    public int getCost()                { return cost; }
    public LocalDateTime getSubmitTime(){ return submitTime; }
    public Status getStatus()           { return status.get(); }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getFinishTime(){ return finishTime; }

    // ---------- state transition ----------

    public void markRunning() {
        status.set(Status.RUNNING);
        startTime = LocalDateTime.now();
    }

    public void markDone() {
        status.set(Status.DONE);
        finishTime = LocalDateTime.now();
    }

    public void markFailed() {
        status.set(Status.FAILED);
        finishTime = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format("Task{id=%s, user=%s, payload='%s', cost=%d, status=%s}",
                taskId, userId, payload, cost, status.get());
    }
}
