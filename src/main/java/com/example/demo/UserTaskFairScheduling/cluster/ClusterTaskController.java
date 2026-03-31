package com.example.demo.UserTaskFairScheduling.cluster;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 集群版任务提交 REST 接口
 *
 * POST /cluster/fair/tasks          提交单个任务
 * POST /cluster/fair/tasks/batch    批量提交任务
 * GET  /cluster/fair/stats          所有用户统计（读自 Redis，全局视图）
 * GET  /cluster/fair/stats/{userId} 指定用户统计
 *
 * 与单机版的区别：
 *   - 路径前缀改为 /cluster/fair，避免与单机版冲突
 *   - 数据统计来自 Redis，无论查哪个实例，返回的都是全局一致的数据
 */
@RestController
@RequestMapping("/cluster/fair")
public class ClusterTaskController {

    private final ClusterFairScheduler scheduler;

    public ClusterTaskController(ClusterFairScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 提交单个任务
     * Body: { "userId": "alice", "payload": "job-001", "cost": 1 }
     *
     * 若该用户队列已满（超过 MAX_USER_QUEUE_SIZE），返回 429 Too Many Requests。
     */
    @PostMapping("/tasks")
    public ResponseEntity<Map<String, String>> submit(@RequestBody SubmitRequest req) {
        ClusterTask task = new ClusterTask(req.userId(), req.payload(),
                req.cost() > 0 ? req.cost() : 1);
        boolean accepted = scheduler.submitTask(task);
        if (!accepted) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "userId", req.userId(),
                    "status", "REJECTED",
                    "reason", "用户队列已满（上限 " + ClusterFairScheduler.MAX_USER_QUEUE_SIZE + "），请稍后重试"
            ));
        }
        return ResponseEntity.ok(Map.of(
                "taskId", task.getTaskId(),
                "userId", task.getUserId(),
                "status", "QUEUED"
        ));
    }

    /**
     * 批量提交任务（模拟大用户涌入）
     * Body: { "userId": "alice", "count": 50, "cost": 1 }
     *
     * 返回实际入队数量与被拒绝数量。
     */
    @PostMapping("/tasks/batch")
    public ResponseEntity<Map<String, Object>> batchSubmit(@RequestBody BatchRequest req) {
        int count    = Math.min(req.count(), 200);
        int queued   = 0;
        int rejected = 0;
        for (int i = 0; i < count; i++) {
            ClusterTask task = new ClusterTask(
                    req.userId(),
                    req.userId() + "-job-" + i,
                    req.cost() > 0 ? req.cost() : 1);
            if (scheduler.submitTask(task)) {
                queued++;
            } else {
                rejected++;
            }
        }
        HttpStatus status = rejected == 0 ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status).body(Map.of(
                "userId",   req.userId(),
                "queued",   queued,
                "rejected", rejected
        ));
    }

    /** 所有用户统计（全局视图，数据来自 Redis） */
    @GetMapping("/stats")
    public List<Map<String, Object>> stats() {
        return scheduler.allStats();
    }

    /** 指定用户统计 */
    @GetMapping("/stats/{userId}")
    public Map<String, Object> userStats(@PathVariable String userId) {
        return scheduler.userStats(userId);
    }

    // ---------- request records ----------

    public record SubmitRequest(String userId, String payload, int cost) {}
    public record BatchRequest(String userId, int count, int cost) {}
}
