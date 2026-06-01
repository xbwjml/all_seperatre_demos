package com.example.demo.ShardingSphereCases.orderCase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单分库分表演示模块配置。
 *
 * <p>演示版规模: 默认 2 库 × 4 表 = 8 张订单表 + 8 张订单明细表，
 * 与文档生产规模 (16 库 × 64 表) 算法形态完全一致，仅缩减规模便于本机演示。</p>
 */
@Data
@ConfigurationProperties(prefix = "demo.order-sharding")
public class OrderShardingProperties {

    /** 是否启用模块。默认关闭，避免影响其他演示模块。 */
    private boolean enabled = false;

    /** JDBC 基础 URL（不带 schema 名） */
    private String baseUrl = "jdbc:mysql://localhost:3306/";

    /** JDBC URL 参数（带前导问号） */
    private String urlParams = "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";

    /** 物理库 schema 名列表（即分库），下标 i 对应 ShardingSphere 中的 ds{i} */
    private List<String> schemas = new ArrayList<>(List.of("order_db_0", "order_db_1"));

    private String username = "root";
    private String password = "root";

    /** 每个分库下的物理表数（等于 t_order_x 与 t_order_item_x 各自的数量） */
    private int tableCountPerDb = 4;

    /** 启动时是否自动建库建表 */
    private boolean initSchema = false;

    /** 雪花 ID workerId（[0, 15]，多实例必须不同） */
    private long workerId = 0L;

    /** HikariCP 连接池上限（每个分库） */
    private int maxPoolSize = 10;

    public int getDbCount() {
        return schemas == null ? 0 : schemas.size();
    }

    public int getTotalShards() {
        return getDbCount() * tableCountPerDb;
    }
}
