package com.example.demo.beanPostProcessor.cases.case4_monitor;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模拟启动时预热数据的服务（如加载商品类目树、权限规则到本地缓存）。
 *
 * <p>设置了较低的告警阈值（200ms），若初始化超时说明 DB/Redis 有问题需要排查。</p>
 */
@Service
@SlowInitAlert(thresholdMs = 200)
public class DataPreloadService {

    private static final Logger log = LoggerFactory.getLogger(DataPreloadService.class);

    @PostConstruct
    public void preload() throws InterruptedException {
        log.info("[DataPreloadService] 开始预热本地缓存...");
        Thread.sleep(350); // 模拟慢初始化：从 DB 加载类目树、权限规则等
        log.info("[DataPreloadService] 缓存预热完成，共加载 1024 条规则");
    }
}
