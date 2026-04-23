package com.example.demo.esDemo.merchandiseSearch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 商品搜索模块配置属性装配。
 *
 * <p>模块整体开关由 {@code demo.merchandise-search.enabled=true} 控制。
 * 模块内的每一个 Bean（Controller/Service/Seeder）都带有相同的
 * {@code @ConditionalOnProperty}，因此关闭时本模块完全沉默，
 * 不要求本地具备 Elasticsearch 环境。
 */
@Configuration
@ConditionalOnProperty(prefix = "demo.merchandise-search", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(MerchandiseSearchProperties.class)
public class MerchandiseSearchAutoConfig {
}
