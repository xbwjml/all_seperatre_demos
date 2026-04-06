package com.example.demo.hiveCases.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * MySQL 数据源配置。
 *
 * <p>仅在 {@code demo.hive-sync.enabled=true} 时激活，避免影响其他模块。
 * 配置项前缀为 {@code demo.hive-sync.mysql}，示例：
 * <pre>
 * demo:
 *   hive-sync:
 *     enabled: true
 *     mysql:
 *       url: jdbc:mysql://localhost:3306/order_db?useSSL=false&serverTimezone=Asia/Shanghai
 *       username: root
 *       password: root
 *       driver-class-name: com.mysql.cj.jdbc.Driver
 * </pre>
 */
@Configuration
@ConditionalOnProperty(name = "demo.hive-sync.enabled", havingValue = "true")
public class MysqlDataSourceConfig {

    @Bean("mysqlDataSource")
    @ConfigurationProperties(prefix = "demo.hive-sync.mysql")
    public DataSource mysqlDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("mysqlJdbcTemplate")
    public JdbcTemplate mysqlJdbcTemplate() {
        return new JdbcTemplate(mysqlDataSource());
    }
}
