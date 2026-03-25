package com.example.demo.beanPostProcessor.cases.case3_lock;

import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Case 3】分布式锁自动代理处理器
 *
 * <p><b>面试要点：</b>
 * <ul>
 *   <li>使用 {@code postProcessAfterInitialization}（Bean 完全初始化后）生成 CGLIB 代理，
 *       这是 Spring AOP 的标准做法。如果在 Before 阶段生成代理，
 *       后续的 @PostConstruct 等初始化回调会作用在代理上，可能引发问题。</li>
 *   <li>实现 {@code BeanFactoryAware}，延迟获取 {@code StringRedisTemplate}（在方法调用时才 getBean），
 *       避免 BPP 早期初始化导致 Redis 自动配置尚未完成的循环依赖问题。</li>
 *   <li>SpEL 解析方法参数名：依赖 {@code DefaultParameterNameDiscoverer}，
 *       Spring Boot 3.2+ 编译时默认携带 -parameters 参数，无需额外配置。</li>
 *   <li>释放锁用 Lua 脚本保证原子性（GET + DEL），防止误删其他线程的锁。</li>
 * </ul>
 * </p>
 *
 * <p><b>执行时机：</b>
 * <pre>
 *   Bean 实例化
 *     ↓
 *   postProcessBeforeInitialization
 *     ↓
 *   @PostConstruct / afterPropertiesSet
 *     ↓
 *   postProcessAfterInitialization  ← 【此处检测注解，生成 CGLIB 代理】
 *     ↓
 *   容器中存放的是代理对象，原始 Bean 被代理包装
 * </pre>
 * </p>
 */
@Component
public class DistributedLockPostProcessor implements BeanPostProcessor, BeanFactoryAware {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockPostProcessor.class);

    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer PARAM_DISCOVERER = new DefaultParameterNameDiscoverer();

    /** 释放锁 Lua 脚本：仅删除 value 匹配的 key，防止超时后误删其他线程的锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end",
            Long.class
    );

    private BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!hasDistributedLockMethod(bean.getClass())) {
            return bean;
        }
        log.info("[DistributedLock] beanName='{}' 检测到 @DistributedLock 方法，生成 CGLIB 代理", beanName);
        return createProxy(bean);
    }

    private boolean hasDistributedLockMethod(Class<?> clazz) {
        AtomicBoolean found = new AtomicBoolean(false);
        ReflectionUtils.doWithMethods(clazz,
                method -> found.set(true),
                method -> method.isAnnotationPresent(DistributedLock.class));
        return found.get();
    }

    private Object createProxy(Object target) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true); // 强制 CGLIB（目标类无需实现接口）

        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            Method method = invocation.getMethod();
            DistributedLock lockAnnotation = method.getAnnotation(DistributedLock.class);
            if (lockAnnotation == null) {
                return invocation.proceed();
            }

            String lockKey = resolveSpelKey(lockAnnotation.key(), method, invocation.getArguments());
            String lockValue = UUID.randomUUID().toString(); // 线程唯一标识，防误删

            StringRedisTemplate redisTemplate = beanFactory.getBean(StringRedisTemplate.class);

            boolean locked = Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(
                            lockKey, lockValue,
                            lockAnnotation.expire(), lockAnnotation.timeUnit()
                    )
            );

            if (!locked) {
                throw new IllegalStateException(
                        "操作频繁，请稍后重试（分布式锁竞争失败）: key=" + lockKey);
            }

            log.info("[DistributedLock] 加锁成功 key={} expire={}{}",
                    lockKey, lockAnnotation.expire(),
                    lockAnnotation.timeUnit().name().toLowerCase());
            try {
                return invocation.proceed();
            } finally {
                // Lua 原子释放：只释放属于自己的锁
                redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockValue);
                log.info("[DistributedLock] 释放锁 key={}", lockKey);
            }
        });

        return proxyFactory.getProxy();
    }

    /**
     * 解析 SpEL key 表达式，将方法参数名映射为变量，支持 #skuId / #userId 等写法。
     *
     * <p>原理：{@code DefaultParameterNameDiscoverer} 在 Spring Boot 3.2+ 下通过
     * {@code -parameters} 编译参数读取真实参数名；旧版本则通过 ASM 读取字节码局部变量表。</p>
     */
    private String resolveSpelKey(String spelExpression, Method method, Object[] args) {
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                null, method, args, PARAM_DISCOVERER);
        return SPEL_PARSER.parseExpression(spelExpression).getValue(context, String.class);
    }
}
