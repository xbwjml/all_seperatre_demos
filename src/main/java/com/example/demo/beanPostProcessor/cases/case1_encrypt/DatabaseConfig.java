package com.example.demo.beanPostProcessor.cases.case1_encrypt;

import org.springframework.stereotype.Component;

/**
 * 模拟数据库连接配置 Bean。
 *
 * <p>互联网公司安全规范：禁止在代码/配置文件中存储明文密码。
 * 常见方案是写入密文（ENC 格式），由框架统一解密，业务代码无感知。</p>
 *
 * <p>此处用 Base64 演示密文格式：
 * <ul>
 *   <li>ENC(cm9vdA==)       → root</li>
 *   <li>ENC(cGFzc3dvcmQxMjM=) → password123</li>
 * </ul>
 * 生产环境通常对接 KMS（阿里云 KMS / AWS KMS / Vault），解密逻辑在 BPP 中统一实现。
 * </p>
 */
@Component
public class DatabaseConfig {

    private String url = "jdbc:mysql://rm-bp1xxx.mysql.rds.aliyuncs.com:3306/order_db";

    @EncryptedValue
    private String username = "ENC(cm9vdA==)";

    @EncryptedValue
    private String password = "ENC(cGFzc3dvcmQxMjM=)";

    public String getUrl() { return url; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }

    @Override
    public String toString() {
        return "DatabaseConfig{url='" + url + "', username='" + username + "', password='[PROTECTED]'}";
    }
}
