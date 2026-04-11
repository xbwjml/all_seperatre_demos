package com.example.demo.localMsgTable.payStock.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class PayStockDataSourceConfig {

    @Bean("payStockDataSource")
    @ConfigurationProperties(prefix = "demo.pay-stock.mysql")
    public DataSource payStockDataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean("payStockJdbcTemplate")
    public JdbcTemplate payStockJdbcTemplate() {
        return new JdbcTemplate(payStockDataSource());
    }

    @Bean("payStockTransactionManager")
    public PlatformTransactionManager payStockTransactionManager() {
        return new DataSourceTransactionManager(payStockDataSource());
    }
}
