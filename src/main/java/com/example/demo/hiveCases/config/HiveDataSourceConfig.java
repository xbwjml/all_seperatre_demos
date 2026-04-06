package com.example.demo.hiveCases.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Hive 数据源配置（通过 HiveServer2 JDBC 连接）。
 *
 * <p>依赖 {@code hive-jdbc} 驱动，pom.xml 中需添加：
 * <pre>
 * &lt;dependency&gt;
 *   &lt;groupId&gt;org.apache.hive&lt;/groupId&gt;
 *   &lt;artifactId&gt;hive-jdbc&lt;/artifactId&gt;
 *   &lt;version&gt;3.1.3&lt;/version&gt;
 *   &lt;exclusions&gt;...&lt;/exclusions&gt;
 * &lt;/dependency&gt;
 * </pre>
 *
 * <p>配置项前缀为 {@code demo.hive-sync.hive}，示例：
 * <pre>
 * demo:
 *   hive-sync:
 *     hive:
 *       url: jdbc:hive2://localhost:10000/default
 *       username: hive
 *       password: ""
 *       driver-class-name: org.apache.hive.jdbc.HiveDriver
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "demo.hive-sync.enabled", havingValue = "true")
public class HiveDataSourceConfig {

    @Bean("hiveDataSource")
    @ConfigurationProperties(prefix = "demo.hive-sync.hive")
    public DataSource hiveDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("hiveJdbcTemplate")
    public JdbcTemplate hiveJdbcTemplate() {
        return new JdbcTemplate(hiveDataSource());
    }
}
