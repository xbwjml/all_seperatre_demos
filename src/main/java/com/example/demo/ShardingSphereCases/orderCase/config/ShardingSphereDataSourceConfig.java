package com.example.demo.ShardingSphereCases.orderCase.config;

import com.example.demo.ShardingSphereCases.orderCase.algorithm.OrderDbShardingAlgorithm;
import com.example.demo.ShardingSphereCases.orderCase.algorithm.OrderTblShardingAlgorithm;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * ShardingSphere 5.5.x 原生 YAML 配置, 通过 {@link YamlShardingSphereDataSourceFactory}
 * 构造 DataSource。本配置仅在 {@code demo.order-sharding.enabled=true} 时启用。
 *
 * <p>暴露三个 Bean:</p>
 * <ul>
 *   <li>{@code orderShardingDataSource}: 逻辑数据源(由 ShardingSphere 包装)</li>
 *   <li>{@code orderShardingJdbcTemplate}: 绑定到逻辑数据源的 JdbcTemplate</li>
 *   <li>{@code orderShardingTxManager}: 本地事务管理器(同分片事务安全)</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(OrderShardingProperties.class)
@ConditionalOnProperty(prefix = "demo.order-sharding", name = "enabled", havingValue = "true")
public class ShardingSphereDataSourceConfig {

    @Bean("orderShardingDataSource")
    public DataSource orderShardingDataSource(OrderShardingProperties props)
            throws SQLException, IOException {
        String yaml = buildYaml(props);
        return YamlShardingSphereDataSourceFactory
                .createDataSource(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Bean("orderShardingJdbcTemplate")
    public JdbcTemplate orderShardingJdbcTemplate(DataSource orderShardingDataSource) {
        return new JdbcTemplate(orderShardingDataSource);
    }

    @Bean("orderShardingTxManager")
    public PlatformTransactionManager orderShardingTxManager(DataSource orderShardingDataSource) {
        return new DataSourceTransactionManager(orderShardingDataSource);
    }

    /** 根据配置动态生成 ShardingSphere 5.5.x 原生 YAML 内容。 */
    private String buildYaml(OrderShardingProperties props) {
        StringBuilder sb = new StringBuilder(2048);

        sb.append("mode:\n  type: Standalone\n\n");

        sb.append("dataSources:\n");
        for (int i = 0; i < props.getSchemas().size(); i++) {
            String schema = props.getSchemas().get(i);
            sb.append("  ds").append(i).append(":\n");
            sb.append("    dataSourceClassName: com.zaxxer.hikari.HikariDataSource\n");
            sb.append("    driverClassName: com.mysql.cj.jdbc.Driver\n");
            sb.append("    jdbcUrl: ").append(props.getBaseUrl()).append(schema).append(props.getUrlParams()).append("\n");
            sb.append("    username: ").append(props.getUsername()).append("\n");
            sb.append("    password: ").append(props.getPassword()).append("\n");
            sb.append("    maximumPoolSize: ").append(props.getMaxPoolSize()).append("\n");
        }

        int dbMax = props.getDbCount() - 1;
        int tblMax = props.getTableCountPerDb() - 1;

        sb.append("\nrules:\n");
        sb.append("  - !SHARDING\n");
        sb.append("    tables:\n");
        sb.append("      t_order:\n");
        sb.append("        actualDataNodes: ds${0..").append(dbMax).append("}.t_order_${0..").append(tblMax).append("}\n");
        sb.append("        databaseStrategy:\n");
        sb.append("          standard:\n");
        sb.append("            shardingColumn: user_id\n");
        sb.append("            shardingAlgorithmName: order_db_algo\n");
        sb.append("        tableStrategy:\n");
        sb.append("          standard:\n");
        sb.append("            shardingColumn: user_id\n");
        sb.append("            shardingAlgorithmName: order_tbl_algo\n");
        sb.append("      t_order_item:\n");
        sb.append("        actualDataNodes: ds${0..").append(dbMax).append("}.t_order_item_${0..").append(tblMax).append("}\n");
        sb.append("        databaseStrategy:\n");
        sb.append("          standard:\n");
        sb.append("            shardingColumn: user_id\n");
        sb.append("            shardingAlgorithmName: order_db_algo\n");
        sb.append("        tableStrategy:\n");
        sb.append("          standard:\n");
        sb.append("            shardingColumn: user_id\n");
        sb.append("            shardingAlgorithmName: order_tbl_algo\n");
        sb.append("    bindingTables:\n");
        sb.append("      - t_order,t_order_item\n");
        sb.append("    shardingAlgorithms:\n");
        sb.append("      order_db_algo:\n");
        sb.append("        type: CLASS_BASED\n");
        sb.append("        props:\n");
        sb.append("          strategy: STANDARD\n");
        sb.append("          algorithmClassName: ").append(OrderDbShardingAlgorithm.class.getName()).append("\n");
        sb.append("          db-count: ").append(props.getDbCount()).append("\n");
        sb.append("          table-count-per-db: ").append(props.getTableCountPerDb()).append("\n");
        sb.append("      order_tbl_algo:\n");
        sb.append("        type: CLASS_BASED\n");
        sb.append("        props:\n");
        sb.append("          strategy: STANDARD\n");
        sb.append("          algorithmClassName: ").append(OrderTblShardingAlgorithm.class.getName()).append("\n");
        sb.append("          table-count-per-db: ").append(props.getTableCountPerDb()).append("\n");

        sb.append("\nprops:\n");
        sb.append("  sql-show: true\n");

        return sb.toString();
    }
}
