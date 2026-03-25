package com.example.demo.beanPostProcessor.cases.case1_encrypt;

import java.lang.annotation.*;

/**
 * 标记需要自动解密的配置字段。
 *
 * <p>使用方式：在字段上添加此注解，并将字段值设为 ENC(密文) 格式。
 * Spring 容器启动时，EncryptedValuePostProcessor 会在 Bean 初始化前自动完成解密。</p>
 *
 * <pre>
 *   {@literal @}EncryptedValue
 *   private String password = "ENC(cGFzc3dvcmQxMjM=)";
 * </pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EncryptedValue {
}
