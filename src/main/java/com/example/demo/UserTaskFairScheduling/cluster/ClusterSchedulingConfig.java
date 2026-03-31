package com.example.demo.UserTaskFairScheduling.cluster;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时任务支持，供 ClusterFairScheduler 中的定期清理任务使用。
 */
@Configuration
@EnableScheduling
public class ClusterSchedulingConfig {
}
