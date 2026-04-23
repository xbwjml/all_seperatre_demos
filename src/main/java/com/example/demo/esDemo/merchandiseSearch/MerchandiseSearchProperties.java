package com.example.demo.esDemo.merchandiseSearch;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商品搜索模块配置。
 *
 * <pre>
 * demo:
 *   merchandise-search:
 *     enabled: true
 *     es-base-url: http://localhost:9200
 *     index-name: products
 *     analyzer: standard        # 生产建议 ik_max_word（需安装 IK 插件）
 *     search-analyzer: standard # 生产建议 ik_smart
 *     seed-on-startup: true     # 启动时自动建索引 + 灌样例数据
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "demo.merchandise-search")
public class MerchandiseSearchProperties {

    /** 是否启用商品搜索模块。 */
    private boolean enabled = false;

    /** Elasticsearch HTTP 地址。 */
    private String esBaseUrl = "http://localhost:9200";

    /** 索引名。 */
    private String indexName = "products";

    /** 写入时分词器（title.analyzer）。 */
    private String analyzer = "standard";

    /** 搜索时分词器（title.search_analyzer）。 */
    private String searchAnalyzer = "standard";

    /** 启动时是否自动建索引并写入样例数据。 */
    private boolean seedOnStartup = true;

    /** 主分片数。 */
    private int numberOfShards = 3;

    /** 副本数。 */
    private int numberOfReplicas = 0;
}
