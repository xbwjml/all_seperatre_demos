package com.example.demo.beanPostProcessor.cases.case1_encrypt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import java.util.Base64;

/**
 * 【Case 1】敏感配置自动解密处理器
 *
 * <p><b>面试要点：</b>
 * <ul>
 *   <li>使用 {@code postProcessBeforeInitialization}，在 {@code @PostConstruct} 执行前完成解密，
 *       保证 Bean 完全就绪时字段已是明文。</li>
 *   <li>{@code postProcessAfterInitialization} 返回的仍是原始 Bean 对象（不生成代理），
 *       仅做字段值替换，性能开销极小。</li>
 *   <li>生产升级路径：将 {@link #decrypt} 方法替换为 KMS SDK 调用（阿里云/AWS），
 *       其余代码无需改动，体现了开闭原则。</li>
 * </ul>
 * </p>
 *
 * <p><b>执行时机：</b>
 * <pre>
 *   Bean 实例化
 *     ↓
 *   postProcessBeforeInitialization  ← 【此处解密字段值】
 *     ↓
 *   @PostConstruct / afterPropertiesSet  ← 此时字段已是明文，可安全使用
 *     ↓
 *   postProcessAfterInitialization
 *     ↓
 *   Bean 放入容器
 * </pre>
 * </p>
 */
@Component
public class EncryptedValuePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(EncryptedValuePostProcessor.class);

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            if (!field.isAnnotationPresent(EncryptedValue.class)) {
                return;
            }
            field.setAccessible(true);
            Object value = field.get(bean);
            if (value instanceof String strVal && isEncrypted(strVal)) {
                String plainText = decrypt(strVal);
                field.set(bean, plainText);
                log.info("[EncryptedValue] beanName='{}' field='{}' 解密完成",
                        beanName, field.getName());
            }
        });
        return bean;
    }

    private boolean isEncrypted(String value) {
        return value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }

    /**
     * 解密实现。此处用 Base64 演示，生产环境替换为 KMS SDK：
     * <pre>
     *   // 阿里云 KMS 示例
     *   KmsClient kmsClient = KmsClient.create(config);
     *   DecryptResponse resp = kmsClient.decrypt(new DecryptRequest().setCiphertextBlob(cipherText));
     *   return resp.getPlaintext();
     * </pre>
     */
    private String decrypt(String encryptedValue) {
        String base64 = encryptedValue.substring(ENC_PREFIX.length(),
                encryptedValue.length() - ENC_SUFFIX.length());
        return new String(Base64.getDecoder().decode(base64));
    }
}
