package com.example.demo.beanPostProcessor.cases.case3_lock;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 方法级分布式锁注解。
 *
 * <p>用法：
 * <pre>
 *   // key 支持 SpEL 表达式，#参数名 引用方法入参
 *   {@literal @}DistributedLock(key = "'stock:deduct:' + #skuId", expire = 10)
 *   public boolean deductStock(String skuId, int quantity) { ... }
 * </pre>
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /**
     * Redis 锁 key，支持 SpEL 表达式。
     * 建议格式：'{业务域}:{操作}:{唯一标识}'
     */
    String key();

    /** 锁的最大持有时间，防止宕机死锁 */
    long expire() default 30;

    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
