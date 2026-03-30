package com.example.demo.UserTaskFairScheduling;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 任务提交 REST 接口
 *
 * POST /fair/tasks          提交单个任务
 * POST /fair/tasks/batch    批量提交任务（模拟大用户）
 * GET  /fair/stats          查看所有用户调度统计
 * GET  /fair/stats/{userId} 查看指定用户统计
 */
@RestController
@RequestMapping("/fair")
public class TaskController {

    private final FairTaskScheduler scheduler;

    public TaskController(FairTaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 提交单个任务
     * Body: { "userId": "alice", "payload": "job-001", "cost": 1 }
     */
    @PostMapping("/tasks")
    public ResponseEntity<Map<String, String>> submit(@RequestBody SubmitRequest req) {
        Task task = new Task(req.userId(), req.payload(), req.cost() > 0 ? req.cost() : 1);
        scheduler.submitTask(task);
        return ResponseEntity.ok(Map.of(
                "taskId", task.getTaskId(),
                "userId", task.getUserId(),
                "status", "QUEUED"
        ));
    }

    /**
     * 批量提交任务（模拟大用户一次性涌入大量任务）
     * Body: { "userId": "alice", "count": 50, "cost": 1 }
     */
    @PostMapping("/tasks/batch")
    public ResponseEntity<Map<String, Object>> batchSubmit(@RequestBody BatchRequest req) {
        int count = Math.min(req.count(), 200);     // 最多 200 个，防止误操作
        for (int i = 0; i < count; i++) {
            Task task = new Task(req.userId(), req.userId() + "-job-" + i,
                    req.cost() > 0 ? req.cost() : 1);
            scheduler.submitTask(task);
        }
        return ResponseEntity.ok(Map.of(
                "userId",    req.userId(),
                "submitted", count,
                "status",    "QUEUED"
        ));
    }

    /** 所有用户统计 */
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
