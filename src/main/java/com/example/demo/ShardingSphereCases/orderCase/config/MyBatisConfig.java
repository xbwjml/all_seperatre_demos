package com.example.demo.ShardingSphereCases.orderCase.config;

import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * 订单分库分表模块的 MyBatis 配置。
 *
 * <p>关键点:
 * <ul>
 *   <li>SqlSessionFactory 显式绑定到 ShardingSphere 包装过的 {@code orderShardingDataSource}</li>
 *   <li>@MapperScan 限定在本模块的 mapper 包内, 防止 starter 全局扫描影响其他模块</li>
 *   <li>MybatisAutoConfiguration 已在 application.yml 中 exclude, 这里完全手动构造</li>
 * </ul>
 */
@Configuration
@MapperScan(
        basePackages = "com.example.demo.ShardingSphereCases.orderCase.mapper",
        sqlSessionFactoryRef = "orderShardingSqlSessionFactory"
)
@ConditionalOnProperty(prefix = "demo.order-sharding", name = "enabled", havingValue = "true")
public class MyBatisConfig {

    @Bean("orderShardingSqlSessionFactory")
    public SqlSessionFactory orderShardingSqlSessionFactory(
            @Qualifier("orderShardingDataSource") DataSource dataSource) throws Exception {

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        // 加载本模块独立的 XML 映射目录
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/order-sharding/*.xml"));

        // MyBatis 全局设置
        org.apache.ibatis.session.Configuration mybatisConfig =
                new org.apache.ibatis.session.Configuration();
        mybatisConfig.setMapUnderscoreToCamelCase(true);          // user_id -> userId
        mybatisConfig.setLogImpl(StdOutImpl.class);
        mybatisConfig.setCacheEnabled(false);                     // 与 ShardingSphere 路由组合时建议关闭二级缓存
        factory.setConfiguration(mybatisConfig);

        return factory.getObject();
    }
}
