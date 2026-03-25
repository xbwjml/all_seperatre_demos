package com.example.demo.beanPostProcessor.cases.case4_monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 【Case 4】Bean 初始化耗时监控处理器
 *
 * <p><b>面试要点：</b>
 * <ul>
 *   <li>同时实现 Before 和 After 两个方法，利用"夹心"时序测量 Bean 初始化（含 @PostConstruct）耗时，
 *       这是唯一能在框架层面做到无侵入监控初始化耗时的方式。</li>
 *   <li>BPP 会对容器中所有 Bean 生效，因此可以作为全局监控手段，
 *       在生产环境排查"某个 Bean 初始化慢导致启动超时"的问题。</li>
 *   <li>用 {@code @SlowInitAlert} 注解支持 Bean 级别的自定义阈值，
 *       未加注解的 Bean 使用全局默认阈值（1000ms），减少噪音。</li>
 *   <li>使用 {@code ConcurrentHashMap} 存储开始时间，因为 Spring 可以并行初始化 Bean
 *       （开启 {@code spring.main.lazy-initialization} 等场景）。</li>
 * </ul>
 * </p>
 *
 * <p><b>执行时机：</b>
 * <pre>
 *   Bean 实例化
 *     ↓
 *   postProcessBeforeInitialization  ← 【记录开始时间 T1】
 *     ↓
 *   @PostConstruct / afterPropertiesSet  ← 这段时间的耗时是监控重点
 *     ↓
 *   postProcessAfterInitialization   ← 【计算耗时 T2-T1，超阈值则告警】
 *     ↓
 *   Bean 放入容器
 * </pre>
 * </p>
 *
 * <p><b>扩展方向：</b> 将耗时数据上报到 Prometheus / SkyWalking 自定义指标，
 * 结合 Grafana 大盘实现可视化监控，或在 CI 流水线中断言启动时间不超过阈值。</p>
 */
@Component
public class InitCostMonitorPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(InitCostMonitorPostProcessor.class);

    /** 全局默认告警阈值：未加 @SlowInitAlert 的 Bean 超过 1000ms 才告警，减少噪音 */
    private static final long DEFAULT_THRESHOLD_MS = 1000;

    /** beanName → 初始化开始时间（ms） */
    private final ConcurrentMap<String, Long> startTimeMap = new ConcurrentHashMap<>();

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        startTimeMap.put(beanName, System.currentTimeMillis());
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Long startTime = startTimeMap.remove(beanName);
        if (startTime == null) {
            return bean;
        }

        long costMs = System.currentTimeMillis() - startTime;
        long thresholdMs = resolveThreshold(bean.getClass());

        if (costMs >= thresholdMs) {
            log.warn("[SlowInit] ⚠️  Bean='{}' 初始化耗时 {}ms，超过阈值 {}ms！" +
                            " 请检查 @PostConstruct 是否有 DB 查询、网络调用或大量计算。",
                    beanName, costMs, thresholdMs);
            // 扩展：此处可上报 Prometheus 指标
            // meterRegistry.timer("bean.init.cost", "bean", beanName).record(costMs, MILLISECONDS);
        } else if (log.isDebugEnabled()) {
            log.debug("[InitMonitor] Bean='{}' 初始化耗时 {}ms（阈值 {}ms）", beanName, costMs, thresholdMs);
        }

        return bean;
    }

    /**
     * 从 {@code @SlowInitAlert} 读取自定义阈值，未标注则使用全局默认阈值。
     */
    private long resolveThreshold(Class<?> beanClass) {
        SlowInitAlert annotation = beanClass.getAnnotation(SlowInitAlert.class);
        return annotation != null ? annotation.thresholdMs() : DEFAULT_THRESHOLD_MS;
    }
}
