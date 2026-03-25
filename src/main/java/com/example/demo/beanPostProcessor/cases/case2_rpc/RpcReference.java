package com.example.demo.beanPostProcessor.cases.case2_rpc;

import java.lang.annotation.*;

/**
 * 标记需要注入远程服务代理的字段，对标 Dubbo 的 {@code @DubboReference}。
 *
 * <p>用法：
 * <pre>
 *   {@literal @}RpcReference(version = "2.0.0", group = "user-center", timeout = 1000)
 *   private UserRpcService userRpcService;
 * </pre>
 * </p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RpcReference {

    /** 服务版本号，用于多版本并存场景 */
    String version() default "1.0.0";

    /** 服务分组，用于灰度、隔离场景 */
    String group() default "default";

    /** 调用超时时间（ms） */
    int timeout() default 3000;
}
