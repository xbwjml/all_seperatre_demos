package com.example.demo.UserTaskFairScheduling.cluster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 集群版任务模型——需要序列化为 JSON 存入 Redis。
 *
 * 与单机版 Task 的区别：
 *   - 使用 Jackson 友好的字段（无 AtomicReference，无 LocalDateTime）
 *   - status 使用 String 而非枚举，避免反序列化兼容问题
 *   - @NoArgsConstructor 提供 Jackson 反序列化所需的无参构造
 *   - @Data 生成全部 getter / setter / equals / hashCode / toString
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClusterTask {

    private String taskId;
    private String userId;
    private String payload;
    private int    cost;
    private String status;          // PENDING / RUNNING / DONE / FAILED
    private long   submitTimeEpoch; // Instant.toEpochMilli()
    private long   startTimeEpoch;
    private long   finishTimeEpoch;

    public ClusterTask(String userId, String payload, int cost) {
        this.taskId          = UUID.randomUUID().toString().substring(0, 8);
        this.userId          = userId;
        this.payload         = payload;
        this.cost            = Math.max(cost, 1);
        this.status          = "PENDING";
        this.submitTimeEpoch = Instant.now().toEpochMilli();
    }

    public ClusterTask(String userId, String payload) {
        this(userId, payload, 1);
    }

    // ---------- state transitions ----------

    public void markRunning() {
        this.status         = "RUNNING";
        this.startTimeEpoch = Instant.now().toEpochMilli();
    }

    public void markDone() {
        this.status          = "DONE";
        this.finishTimeEpoch = Instant.now().toEpochMilli();
    }

    public void markFailed() {
        this.status          = "FAILED";
        this.finishTimeEpoch = Instant.now().toEpochMilli();
    }
}
