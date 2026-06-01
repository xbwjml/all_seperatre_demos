package com.example.demo.ShardingSphereCases.orderCase.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 启动时根据 {@link OrderShardingProperties} 自动:
 * <ol>
 *   <li>用 root 连接(不带 schema)创建配置中的全部 schema</li>
 *   <li>在每个 schema 下创建 t_order_${i} / t_order_item_${i} 物理表</li>
 * </ol>
 * 仅在 {@code demo.order-sharding.enabled=true} 且 {@code init-schema=true} 时执行。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "demo.order-sharding", name = {"enabled", "init-schema"}, havingValue = "true")
public class OrderSchemaInitializer implements ApplicationRunner {

    private static final String CREATE_ORDER_TPL = """
            CREATE TABLE IF NOT EXISTS t_order_%d (
              id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键(本表内自增)',
              order_id      BIGINT       NOT NULL COMMENT '业务订单号(雪花+基因)',
              order_no      VARCHAR(32)  NOT NULL COMMENT '展示用订单号',
              user_id       BIGINT       NOT NULL COMMENT '买家ID(分片键)',
              seller_id     BIGINT       NOT NULL,
              total_amount  DECIMAL(18,2) NOT NULL,
              pay_amount    DECIMAL(18,2) NOT NULL,
              status        TINYINT      NOT NULL COMMENT '0待支付 1已支付 2已发货 3已完成 4已取消 5退款中',
              pay_type      TINYINT      DEFAULT NULL,
              create_time   DATETIME(3)  NOT NULL,
              update_time   DATETIME(3)  NOT NULL,
              pay_time      DATETIME(3)  DEFAULT NULL,
              remark        VARCHAR(255) DEFAULT NULL,
              version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
              PRIMARY KEY (id),
              UNIQUE KEY uk_order_id (order_id),
              KEY idx_user_create (user_id, create_time),
              KEY idx_seller_create (seller_id, create_time),
              KEY idx_status_update (status, update_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final String CREATE_ITEM_TPL = """
            CREATE TABLE IF NOT EXISTS t_order_item_%d (
              id          BIGINT       NOT NULL AUTO_INCREMENT,
              order_id    BIGINT       NOT NULL,
              user_id     BIGINT       NOT NULL COMMENT '冗余分片键',
              sku_id      BIGINT       NOT NULL,
              spu_id      BIGINT       NOT NULL,
              sku_name    VARCHAR(255) NOT NULL,
              price       DECIMAL(18,2) NOT NULL,
              quantity    INT          NOT NULL,
              create_time DATETIME(3)  NOT NULL,
              PRIMARY KEY (id),
              KEY idx_order_id (order_id),
              KEY idx_sku (sku_id, create_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private final OrderShardingProperties props;

    public OrderSchemaInitializer(OrderShardingProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("[OrderShardingCase] init schemas: {}, tables/db: {}",
                props.getSchemas(), props.getTableCountPerDb());

        createSchemas();
        for (String schema : props.getSchemas()) {
            createTablesIn(schema);
        }
        log.info("[OrderShardingCase] schema init finished");
    }

    private void createSchemas() throws SQLException {
        String url = props.getBaseUrl() + props.getUrlParams();
        try (Connection conn = DriverManager.getConnection(url, props.getUsername(), props.getPassword());
             Statement stmt = conn.createStatement()) {
            for (String schema : props.getSchemas()) {
                String sql = "CREATE DATABASE IF NOT EXISTS `" + schema
                        + "` DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci";
                stmt.executeUpdate(sql);
                log.info("[OrderShardingCase] CREATE DATABASE {}", schema);
            }
        }
    }

    private void createTablesIn(String schema) throws SQLException {
        String url = props.getBaseUrl() + schema + props.getUrlParams();
        try (Connection conn = DriverManager.getConnection(url, props.getUsername(), props.getPassword());
             Statement stmt = conn.createStatement()) {
            for (int i = 0; i < props.getTableCountPerDb(); i++) {
                stmt.executeUpdate(String.format(CREATE_ORDER_TPL, i));
                stmt.executeUpdate(String.format(CREATE_ITEM_TPL, i));
            }
            log.info("[OrderShardingCase] init {} tables in {}", props.getTableCountPerDb() * 2, schema);
        }
    }
}
