package com.example.demo.beanPostProcessor.cases.case4_monitor;

import java.lang.annotation.*;

/**
 * 标记需要监控初始化耗时的 Bean，可自定义告警阈值。
 *
 * <p>不加此注解的 Bean 也会被全局监控（使用默认阈值），
 * 加了此注解可为特定 Bean 设置更低的阈值（如核心服务要求 100ms 内完成初始化）。</p>
 *
 * <pre>
 *   {@literal @}Service
 *   {@literal @}SlowInitAlert(thresholdMs = 200)
 *   public class DataPreloadService {
 *       {@literal @}PostConstruct
 *       public void preload() { ... } // 超 200ms 将触发告警
 *   }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SlowInitAlert {

    /** 初始化耗时告警阈值（ms），默认 500ms */
    long thresholdMs() default 500;
}
