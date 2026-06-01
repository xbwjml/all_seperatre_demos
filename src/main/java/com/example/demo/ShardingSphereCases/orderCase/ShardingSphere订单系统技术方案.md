# 基于 ShardingSphere 的订单系统技术方案

> 版本: v1.0
> 编写日期: 2026-04-30
> 技术栈: Java 21 + Spring Boot 3.3.x + ShardingSphere 5.5.x + MySQL 8 + MyBatis-Plus + Redis + RocketMQ
> 文档定位: 面向研发团队的落地级技术设计文档
> 适用规模: 订单年增量 5 亿 ~ 50 亿, 单表控制在 1000 万以内

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [订单系统核心痛点分析](#2-订单系统核心痛点分析)
3. [总体技术架构](#3-总体技术架构)
4. [分片策略设计 (核心)](#4-分片策略设计-核心)
5. [数据模型与 DDL](#5-数据模型与-ddl)
6. [分布式 ID 方案](#6-分布式-id-方案)
7. [读写分离设计](#7-读写分离设计)
8. [跨分片查询难题与解决](#8-跨分片查询难题与解决)
9. [Spring Boot 集成与代码示例](#9-spring-boot-集成与代码示例)
10. [性能容量规划](#10-性能容量规划)

---

## 1. 背景与目标

### 1.1 业务背景

订单系统是电商/交易类业务的核心，具有以下典型特征：

- **数据量大**：日订单 100 万 ~ 1000 万，年累计十亿级
- **写入压力高**：大促期间 QPS 可达 10 万 +
- **查询维度多**：买家维度、卖家维度、订单号维度、商品维度
- **数据生命周期长**：业务上需保留 3 年以上（金融场景需要 10 年）
- **强一致性要求**：金额、库存、状态机不能出错

### 1.2 设计目标

| 维度 | 目标 |
|------|------|
| 单表数据量 | 控制在 1000 万以内 |
| 写入 TPS | 支撑大促期间 5 万 TPS |
| 查询 RT | 用户维度查询 P99 < 100ms |
| 扩展性 | 支持在线扩容，无需停机 |
| 数据一致性 | 主流程强一致，统计查询最终一致 |
| 改造成本 | 业务代码侵入 < 5%，SQL 改造 < 10% |

### 1.3 为什么选 ShardingSphere

| 候选方案 | 优势 | 劣势 | 是否选用 |
|---------|------|------|---------|
| **ShardingSphere-JDBC** | 性能高、零中间层、Java 生态完善 | 仅支持 Java | ✅ 主选 |
| MyCat | 协议层代理，多语言 | 社区不活跃，bug 多 | ❌ |
| TiDB | 原生分布式，无需分片 | 迁移成本高，运维复杂 | ❌ 短期不选 |
| 自研中间件 | 完全可控 | 研发投入大 | ❌ |

最终选型：**ShardingSphere-JDBC 5.5.x** 作为主要方案，对 BI / DBA 分析场景叠加 **ShardingSphere-Proxy** 提供统一查询入口。

---

## 2. 订单系统核心痛点分析

### 2.1 单库单表的瓶颈

```
单表 5000 万行后:
- B+Tree 高度从 3 → 4，索引查询变慢
- DDL（加索引、加字段）锁表时间从分钟级 → 小时级
- 备份与恢复时间窗口超过 4 小时
- 大事务回滚时间长
- 主从延迟显著增大
```

### 2.2 订单的多维查询挑战

订单系统天然存在 **"一对多对多"** 的查询场景：

| 查询方维度 | 典型场景 | 占比 |
|----------|---------|------|
| 买家 (user_id) | "我的订单"列表 | 60% |
| 订单号 (order_id) | 订单详情、客服查询 | 25% |
| 卖家 (seller_id) | 商家订单管理 | 10% |
| 商品 (sku_id) | 销量统计 | 3% |
| 时间区间 | 报表、对账 | 2% |

> **核心矛盾**：分片键只能选一个，但查询维度有多个 → 必须用 **基因法 + 异构索引** 组合解决。

### 2.3 强一致性要求

- 订单主表与订单明细必须落在同一分片（绑定表），保证本地事务即可
- 订单状态机：待支付 → 已支付 → 已发货 → 已完成 / 已取消
- 状态变更必须满足"幂等 + 不可逆"

---

## 3. 总体技术架构

### 3.1 整体架构图

```
┌────────────────────────────────────────────────────────────────────┐
│                         接入层 / 网关                                │
│              Spring Cloud Gateway / Nginx                          │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
┌──────────────────────────────▼─────────────────────────────────────┐
│                       订单服务集群 (Java 21)                         │
│   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐  │
│   │ 下单服务     │ │ 查询服务     │ │ 履约服务     │ │ 售后服务     │  │
│   └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘  │
│                  ▲                                                  │
│                  │ 集成 ShardingSphere-JDBC 5.5.x                   │
└──────────────────┼──────────────────────────────────────────────────┘
                   │
        ┌──────────┴──────────────────────────┐
        │                                     │
        ▼                                     ▼
┌──────────────────────┐          ┌──────────────────────┐
│  写路径 (主分片库)     │          │  读路径 (异构索引)     │
│  ds_0 ~ ds_15 (16库)  │          │  ElasticSearch        │
│  每库 64 张 t_order   │          │  - 订单号倒查         │
│  共 1024 张表         │          │  - 卖家维度查询        │
│                      │          │  - 复合条件搜索        │
└──────────┬───────────┘          └──────────▲───────────┘
           │                                  │
           │ MySQL Binlog                     │ Canal / Flink CDC
           └──────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│   消息层: RocketMQ (订单事件、状态变更事件)                           │
│   缓存层: Redis (热点订单缓存、库存扣减 Lua、幂等 Token)              │
│   ID 生成: Leaf-Snowflake (基因法预留 user_id 后 10 位)              │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流向

| 流程 | 路径 |
|------|------|
| 下单写入 | 应用 → ShardingSphere-JDBC → MySQL 分片 |
| 用户查询订单 | 应用 → ShardingSphere（按 user_id 路由） → MySQL 单分片 |
| 订单号查询 | 应用 → 解析订单号基因 → 路由到对应分片 |
| 卖家查询 | 应用 → ElasticSearch（异构索引） |
| 报表统计 | Binlog → Canal → Kafka → Flink → ClickHouse |

---

## 4. 分片策略设计 (核心)

### 4.1 分片键选择

**分片键: `user_id`（买家 ID）**

选择理由：
- 60% 以上的查询是"我的订单"，必带 user_id
- 用户行为天然分散，不会出现严重热点
- 一个用户的订单天然内聚，便于事务

### 4.2 分片规模规划

按"5 年数据全量保留"目标倒推：

```
假设峰值: 1000 万订单 / 天
5 年累计: 1000 万 × 365 × 5 ≈ 180 亿
单表容量: 1000 万
所需分表数: 180 亿 / 1000 万 = 1800 张

实际规划: 16 库 × 64 表 = 1024 张表 (预留 50% 容量)
后续不够: 通过历史数据归档 + 翻倍扩容
```

### 4.3 分片算法

采用 **二次取模** 算法：

```
db_index    = (user_id % 1024) / 64    →  0..15
table_index = user_id % 64             →  0..63

例: user_id = 100086
db_index    = (100086 % 1024) / 64 = 870 / 64 = 13
table_index = 100086 % 64          = 38
→ 落在 ds_13.t_order_38
```

**为什么不用 `user_id % 16` 和 `user_id % 64` 各算一次？**
因为这两个 % 不互质，会导致同一 user_id 在不同库的不同表上分布不均。**二次取模法**保证 `(user_id % 1024)` 唯一定位到 1024 张分表中的一张。

### 4.4 分片配置 (YAML)

> **注意**: ShardingSphere 从 5.0 起官方**不再提供** spring-boot-starter，4.x 的 `spring.shardingsphere.*` 配置在 5.5.x 中**不会被解析**。
> 正确做法是使用 ShardingSphere **原生 YAML 文件** + `YamlShardingSphereDataSourceFactory` 构造 DataSource，或使用 `ShardingSphereDriver` 的 `jdbc:shardingsphere:classpath:xxx.yaml` URL 模式。

放在 `src/main/resources/META-INF/order-sharding.yaml`:

```yaml
mode:
  type: Standalone

dataSources:
  ds0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: jdbc:mysql://mysql-0.internal:3306/order_db_0?useSSL=false&serverTimezone=Asia/Shanghai
    username: order_rw
    password: ${DB_PASSWORD}
    maximumPoolSize: 50
  # ds1 ~ ds15 同结构, 略

rules:
  - !SHARDING
    tables:
      t_order:
        actualDataNodes: ds${0..15}.t_order_${0..63}
        databaseStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithmName: order_db_algo
        tableStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithmName: order_tbl_algo
      t_order_item:
        actualDataNodes: ds${0..15}.t_order_item_${0..63}
        databaseStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithmName: order_db_algo
        tableStrategy:
          standard:
            shardingColumn: user_id
            shardingAlgorithmName: order_tbl_algo
    bindingTables:
      - t_order,t_order_item        # 绑定表, 避免笛卡尔积
    shardingAlgorithms:
      order_db_algo:
        type: CLASS_BASED
        props:
          strategy: STANDARD
          algorithmClassName: com.example.demo.ShardingSphereCases.orderCase.algorithm.OrderDbShardingAlgorithm
      order_tbl_algo:
        type: CLASS_BASED
        props:
          strategy: STANDARD
          algorithmClassName: com.example.demo.ShardingSphereCases.orderCase.algorithm.OrderTblShardingAlgorithm

  # 5.5.x 起, 广播表是顶级独立规则
  - !BROADCAST
    tables:
      - t_dict_region
      - t_dict_order_status

props:
  sql-show: true
  kernel-executor-size: 32
```

> **关于 KeyGenerator**: 由于 order_id 需要植入 user_id 基因, 而 ShardingSphere 的 `KeyGenerateAlgorithm` SPI 拿不到 user_id 上下文, 因此**不**在 ShardingSphere 中配置 KeyGenerator, 由业务层显式生成 order_id (见 9.3 / 9.4 节)。

接入到 Spring Boot 有两种等价方式, 推荐**方式 A**（更可控、利于后续监控接入）:

**方式 A: 通过 `@Configuration` 暴露 DataSource Bean**

```java
@Configuration
public class ShardingSphereDataSourceConfig {

    @Bean
    public DataSource shardingSphereDataSource() throws SQLException, IOException {
        File yamlFile = new ClassPathResource("META-INF/order-sharding.yaml")
            .getFile();
        return YamlShardingSphereDataSourceFactory.createDataSource(yamlFile);
    }
}
```

**方式 B: 用 ShardingSphereDriver URL 模式 (`application.yml`)**

```yaml
spring:
  datasource:
    driver-class-name: org.apache.shardingsphere.driver.ShardingSphereDriver
    url: jdbc:shardingsphere:classpath:META-INF/order-sharding.yaml
```

---

## 5. 数据模型与 DDL

### 5.1 核心表设计

#### 5.1.1 订单主表 `t_order`

```sql
CREATE TABLE t_order_0 (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键(本表内自增, 用作 InnoDB 物理顺序)',
  order_id        BIGINT       NOT NULL COMMENT '业务订单号(雪花+基因, 由业务层生成)',
  order_no        VARCHAR(32)  NOT NULL COMMENT '展示用订单号',
  user_id         BIGINT       NOT NULL COMMENT '买家ID(分片键)',
  seller_id       BIGINT       NOT NULL COMMENT '卖家ID',
  total_amount    DECIMAL(18,2) NOT NULL,
  pay_amount      DECIMAL(18,2) NOT NULL,
  status          TINYINT      NOT NULL COMMENT '0待支付 1已支付 2已发货 3已完成 4已取消 5退款中',
  pay_type        TINYINT      DEFAULT NULL,
  create_time     DATETIME(3)  NOT NULL,
  update_time     DATETIME(3)  NOT NULL,
  pay_time        DATETIME(3)  DEFAULT NULL,
  ext_info        JSON         DEFAULT NULL,
  version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_id (order_id),
  KEY idx_user_create (user_id, create_time),
  KEY idx_seller_create (seller_id, create_time),
  KEY idx_status_update (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表-分片0';
-- t_order_1 ~ t_order_63 同结构
```

#### 5.1.2 订单明细表 `t_order_item`

```sql
CREATE TABLE t_order_item_0 (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键(本表内自增)',
  order_id        BIGINT       NOT NULL,
  user_id         BIGINT       NOT NULL COMMENT '冗余分片键',
  sku_id          BIGINT       NOT NULL,
  spu_id          BIGINT       NOT NULL,
  sku_name        VARCHAR(255) NOT NULL,
  price           DECIMAL(18,2) NOT NULL,
  quantity        INT          NOT NULL,
  create_time     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_order_id (order_id),
  KEY idx_sku (sku_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

#### 5.1.3 订单号-用户路由表 (基因法的备用方案)

> 如果不用基因法，可建立 `order_id → user_id` 的反查表，存 Redis 或独立的小库。

```sql
CREATE TABLE t_order_route (
  order_id  BIGINT PRIMARY KEY,
  user_id   BIGINT NOT NULL,
  shard_key INT    NOT NULL
) ENGINE=InnoDB;
```

### 5.2 表设计原则

| 原则 | 说明 |
|------|------|
| 分片键冗余 | 子表（t_order_item）必须冗余 user_id |
| 字段精简 | 大字段（备注、扩展信息）拆到单独表或用 JSON |
| 时间字段统一 | 全部 DATETIME(3)，UTC 存储 |
| 状态机字段 | 用 TINYINT，避免 VARCHAR 状态 |
| 软删除 | 不使用 deleted 字段，归档表替代 |
| 主键策略 | 每张物理表自增 id（用作 InnoDB 物理顺序），业务主键 order_id |

---

## 6. 分布式 ID 方案

### 6.1 订单号必须满足

- 全局唯一
- 趋势递增（B+Tree 友好）
- 不能从订单号反推业务量
- **能从订单号路由到分片**（关键！）

### 6.2 基因法 (Gene Method)

将 user_id 的低位"植入"到 order_id 中：

```
order_id 64 bit 结构 (改造版雪花):
┌──────┬──────────────────┬──────────┬──────────────┬───────────────┐
│ 1bit │      41 bit       │  4 bit   │    8 bit     │    10 bit     │
│  符号  │   毫秒时间戳       │ 机器ID   │   序列号      │  user_id 基因 │
└──────┴──────────────────┴──────────┴──────────────┴───────────────┘
```

- 总位宽: 1 + 41 + 4 + 8 + 10 = 64 bit
- 基因 = `user_id & 0x3FF` (取低 10 位，对应 1024 = 16 库 × 64 表)
- 机器 ID 4 bit 支持 16 个 ID 生成节点
- 序列号 8 bit 支持单节点单毫秒 256 个 ID（理论 256K TPS / 节点）

**反向路由**：
```java
// 仅有 order_id 也能算出分片
long gene = orderId & 0x3FF;          // 取低 10 位
int  dbIndex   = (int)(gene / 64);
int  tableIndex = (int)(gene % 64);
```

### 6.3 实现方案

| 方案 | 适用 | 说明 |
|------|------|------|
| **Leaf-Snowflake (改造版)** | 主选 | 美团 Leaf 改造，加基因位 |
| **Uid-Generator** | 备选 | 百度方案，依赖 ZK |
| **数据库号段** | 不适合 | 不带时间戳，性能瓶颈 |

> 实际实现见 9.3 节 `GeneSnowflakeKeyGenerator`，由本地 SDK 生成（也可改造为独立 ID 服务）。

---

## 7. 读写分离设计

### 7.1 部署拓扑

每个分片采用 **一主两从** 结构：

```
ds_0_master (写)  ─┬─ ds_0_slave_0 (读)
                   └─ ds_0_slave_1 (读)
... ds_1 ~ ds_15 同结构
```

### 7.2 ShardingSphere 配置

```yaml
rules:
  readwrite-splitting:
    data-sources:
      ds0:
        type: Static
        props:
          write-data-source-name: ds_0_master
          read-data-source-names: ds_0_slave_0,ds_0_slave_1
        load-balancer-name: round-robin
      # ds1 ~ ds15 同
    load-balancers:
      round-robin:
        type: ROUND_ROBIN
```

### 7.3 强制走主库的场景

通过 `HintManager` 强制路由：

```java
try (HintManager hint = HintManager.getInstance()) {
    hint.setWriteRouteOnly();
    // 1. 刚写完立即查（主从延迟未追上）
    // 2. 涉及金额、状态变更的关键查询
    return orderMapper.selectByOrderId(orderId);
}
```

### 7.4 主从延迟应对

| 策略 | 实现 |
|------|------|
| 关键查询强制走主库 | `HintManager.setWriteRouteOnly()` 显式声明 |
| 事务内写后读自动走主 | **ShardingSphere 5.x 默认开启**, 同一事务的 SELECT 自动路由主库, 无需业务代码处理 |
| 监控延迟 | Prometheus + mysql_exporter，> 5 秒告警 |

> 5.x 已经废弃了 4.x 时代需要业务方自己维护 ThreadLocal 标记的做法。如果业务在事务外仍想"写后立即读最新"，使用 `HintManager` 显式声明即可。

---

## 8. 跨分片查询难题与解决

### 8.1 难题清单

| 难题 | 表现 |
|------|------|
| 卖家维度查询 | 不带分片键，需要广播到所有分片 |
| 时间区间报表 | 全分片扫描 + 归并 |
| ORDER BY + LIMIT 跨页 | 深度分页性能极差 |
| COUNT、GROUP BY | 各分片小聚合 + 全局再聚合 |
| 跨用户 JOIN | 几乎不可能高效 |

### 8.2 解决方案矩阵

| 场景 | 方案 |
|------|------|
| **卖家查订单** | 通过 Canal 同步到 ElasticSearch，按 seller_id 倒排 |
| **订单号查订单** | 基因法路由（最优）；或 Redis 缓存 order_id → user_id |
| **运营报表** | Binlog → Flink → ClickHouse / Doris |
| **C 端"我的订单"** | ShardingSphere 直接路由（带 user_id） |
| **客服查询** | 如果带 order_id，基因法；否则走 ES |
| **统计 GMV** | ClickHouse 实时聚合 |

### 8.3 异构索引（ElasticSearch）方案

```
MySQL 分片 → Canal → Kafka → Logstash / 自研消费者 → ES

ES 索引设计:
- 索引名: order_search
- 主键: order_id
- 字段: order_id, user_id, seller_id, sku_ids, status, amount, create_time, ...
- 路由: 按 seller_id route 到固定 shard，提升查询命中率
```

### 8.4 深度分页优化

**禁止**：
```sql
SELECT * FROM t_order WHERE seller_id = ? ORDER BY create_time LIMIT 100000, 10
```
ShardingSphere 会从每个分片取 100010 行做归并，灾难性能。

**推荐：游标分页**

使用 **`order_id`**（雪花趋势递增）作为游标，**不要**使用每张物理表内独立自增的 `id` 字段（在逻辑表层面没有全局有序意义）：

```sql
SELECT * FROM t_order
 WHERE user_id = ? AND order_id < #{lastOrderId}
 ORDER BY order_id DESC
 LIMIT 20
```

首页查询时 `lastOrderId` 传 `Long.MAX_VALUE` 即可。如果业务排序维度是创建时间，用 `(create_time, order_id)` 复合游标避免毫秒并发的不稳定。

---

## 9. Spring Boot 集成与代码示例

### 9.1 Maven 依赖

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc</artifactId>
    <version>5.5.0</version>
</dependency>
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>
```

### 9.2 自定义分片算法

```java
package com.example.demo.ShardingSphereCases.orderCase.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

public class OrderDbShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private static final int DB_COUNT = 16;
    private static final int TBL_COUNT_PER_DB = 64;
    private static final int TOTAL = DB_COUNT * TBL_COUNT_PER_DB;

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Long> shardingValue) {
        long userId = shardingValue.getValue();
        int dbIndex = (int) ((userId % TOTAL) / TBL_COUNT_PER_DB);
        String suffix = "ds" + dbIndex;
        return availableTargetNames.stream()
            .filter(t -> t.equalsIgnoreCase(suffix))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No db found for " + suffix));
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Long> shardingValue) {
        return availableTargetNames;
    }

    @Override
    public Properties getProps() { return new Properties(); }

    @Override
    public void init(Properties props) {}

    @Override
    public String getType() { return "ORDER_DB"; }
}
```

### 9.3 基因雪花 ID 生成器

> **注意**: 基因雪花 ID **不**通过 ShardingSphere 的 `KeyGenerateAlgorithm` SPI 生成。
> 因为 SPI 接口 `generateKey()` 拿不到 user_id 上下文，无法植入基因位。
> 因此 order_id 由**业务层显式调用** `idGen.nextId(userId)` 生成（见 9.4 节）；
> ShardingSphere 配置中**不要**为 t_order 配置 `keyGenerateStrategy`。

```java
package com.example.demo.ShardingSphereCases.orderCase.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基因雪花 ID 生成器:
 *   1 bit 符号 + 41 bit 时间戳 + 4 bit workerId + 8 bit 序列号 + 10 bit 基因 = 64 bit
 * 基因 = user_id & 0x3FF, 与分片算法对齐, 用于"凭 order_id 反向路由分片"。
 */
@Component
public class GeneSnowflakeKeyGenerator {

    private static final long EPOCH        = 1735660800000L; // 2025-01-01 00:00:00 UTC
    private static final int  GENE_BITS    = 10;
    private static final int  SEQ_BITS     = 8;
    private static final int  WORKER_BITS  = 4;
    private static final long MAX_SEQ      = (1L << SEQ_BITS)    - 1; // 255
    private static final long MAX_WORKER   = (1L << WORKER_BITS) - 1; // 15
    private static final long GENE_MASK    = (1L << GENE_BITS)   - 1; // 0x3FF

    /** workerId 由配置注入(见 10 末尾"workerId 分配机制") */
    private final long workerId;
    private long lastTs   = -1L;
    private long sequence = 0L;

    public GeneSnowflakeKeyGenerator(@Value("${order.id.worker-id:0}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException(
                "workerId must be in [0, " + MAX_WORKER + "], got " + workerId);
        }
        this.workerId = workerId;
    }

    public synchronized long nextId(long userId) {
        long now = System.currentTimeMillis();

        // 时钟回拨保护: 小幅(<=5ms)等待, 大幅直接抛异常
        if (now < lastTs) {
            long offset = lastTs - now;
            if (offset <= 5) {
                try {
                    Thread.sleep(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ID generation interrupted", e);
                }
                now = System.currentTimeMillis();
            }
            if (now < lastTs) {
                throw new IllegalStateException(
                    "Clock moved backwards by " + (lastTs - now) + "ms, refuse to generate id");
            }
        }

        if (now == lastTs) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                // 同毫秒序列号耗尽, 自旋到下一毫秒
                while (now <= lastTs) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0;
        }
        lastTs = now;

        long gene = userId & GENE_MASK;
        return ((now - EPOCH) << (WORKER_BITS + SEQ_BITS + GENE_BITS))
             | (workerId       << (SEQ_BITS + GENE_BITS))
             | (sequence       <<  GENE_BITS)
             |  gene;
    }

    /** 凭 order_id 反推 db 下标 (0..15) */
    public static int dbIndex(long orderId) {
        return (int) ((orderId & GENE_MASK) / 64);
    }

    /** 凭 order_id 反推 table 下标 (0..63) */
    public static int tableIndex(long orderId) {
        return (int) ((orderId & GENE_MASK) % 64);
    }
}
```

**workerId 分配机制**（4 bit, 范围 0..15, 多实例必须互不重复）：

| 方案 | 适用环境 | 说明 |
|------|---------|------|
| 配置注入 | 物理机 / 固定实例 | 启动参数 `--order.id.worker-id=3` |
| K8s `StatefulSet` | 云原生 | 用 Pod 序号 (`pod-name-0`, `pod-name-1` ...) 解析后注入 |
| ZooKeeper / Etcd 自动注册 | 弹性扩缩容 | 启动时申请一个未占用的 workerId, 存活期间锁定 |
| 数据库取号 | 简单可靠 | `t_worker_id` 表 + 行锁分配, 心跳续约 |

> 节点超过 16 个时, 需要扩展 `WORKER_BITS`, 同时减少 `SEQ_BITS` (例如 5 + 7 + 10 = 22, 总和不变)。

### 9.4 下单服务示例

```java
@Service
@RequiredArgsConstructor
public class OrderCreateService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper itemMapper;
    private final GeneSnowflakeKeyGenerator idGen;

    @Transactional(rollbackFor = Exception.class)
    public Long createOrder(CreateOrderCmd cmd) {
        long orderId = idGen.nextId(cmd.getUserId());

        OrderDO order = OrderDO.builder()
            .orderId(orderId)
            .userId(cmd.getUserId())
            .sellerId(cmd.getSellerId())
            .totalAmount(cmd.getTotalAmount())
            .status(OrderStatus.PENDING_PAY.getCode())
            .createTime(LocalDateTime.now())
            .build();
        orderMapper.insert(order);

        List<OrderItemDO> items = cmd.getItems().stream()
            .map(i -> toItemDO(i, orderId, cmd.getUserId()))
            .toList();
        itemMapper.batchInsert(items);

        return orderId;
    }
}
```

### 9.5 订单号查询（基因法路由）

```java
public OrderDO getByOrderId(long orderId) {
    long gene = orderId & 0x3FF;
    return orderMapper.selectByOrderIdWithGene(orderId, gene);
}
```

```xml
<!-- Mapper XML 中通过 user_id 等价的 gene 触发分片路由（需配合 Hint 或自定义算法） -->
<select id="selectByOrderIdWithGene" resultType="OrderDO">
  SELECT * FROM t_order
   WHERE order_id = #{orderId}
     AND user_id = #{gene}    /* 此处 gene 仅用于路由提示，需自定义 Hint 算法 */
</select>
```

> 实际推荐做法：使用 ShardingSphere 的 **Hint 强制路由**，从 order_id 解析出 db_index/table_index 后直接 Hint 注入，不污染 SQL。

---

## 10. 性能容量规划

### 10.1 容量推演

| 维度 | 数值 |
|------|------|
| 分库数 | 16 |
| 单库分表数 | 64 |
| 总分表数 | 1024 |
| 单表预估上限 | 1500 万行 |
| 总容量上限 | 153 亿行 |
| 单机 MySQL TPS | 5000 |
| 写入总 TPS 上限 | 5000 × 16 = 80,000 |

### 10.2 压测目标

| 场景 | 目标 |
|------|------|
| 下单 TPS | 50,000 |
| 用户订单查询 QPS | 200,000 |
| 订单详情查询 QPS | 100,000 |
| P99 RT | < 100ms |

### 10.3 性能优化清单

- [ ] 连接池大小：`maximum-pool-size = (CPU核数 × 2) + 磁盘数`
- [ ] `kernel-executor-size`：等于核数 × 2
- [ ] 批量插入：使用 `rewriteBatchedStatements=true`
- [ ] 避免 `SELECT *`，按需投影
- [ ] 关键查询加 Hint 避免不必要的解析
- [ ] 大查询走只读从库
- [ ] 缓存热点订单（用户最近 10 单）

---

## 附录 A: 常见问题 FAQ

**Q1: 为什么不直接用 TiDB？**
A: 团队 MySQL 经验深、迁移成本低、改造周期短；TiDB 短期不选，长期保留演进可能。

**Q2: 1024 张表会不会太多？**
A: 单实例 64 张表，FD / metadata 完全可承受，比 4096 张更平衡。

**Q3: 卖家维度真的不能走分片库吗？**
A: 可以走，但需要广播查询，性能差。最佳实践是用 ES 做异构索引，分片库只服务买家。

**Q4: 基因法对 user_id 有要求吗？**
A: user_id 最好均匀分布（雪花算法生成的最佳）。如果是自增 ID，分布也均匀，没问题。

**Q5: 历史订单查询很慢怎么办？**
A: 冷热分离 + ES 长尾查询；用户超过半年的订单页加载提示"加载较慢"。

**Q6: 如何避免开发误写出全分片扫描 SQL？**
A: ShardingSphere 5.x 的 `SQL Audit` 特性 + 公司 SQL 评审平台 + 上线前 explain。

---

## 附录 B: 参考资料

- [Apache ShardingSphere 官方文档](https://shardingsphere.apache.org/document/current/cn/overview/)
- 《亿级流量网站架构核心技术》— 张开涛
- 《数据密集型应用系统设计》— Martin Kleppmann
- 京东 ShardingSphere 实战分享
- 美团 Leaf 分布式 ID 方案

---

> 本文档为落地级技术方案，**实际实施前需结合业务真实数据量、查询分布、团队能力进行调整**。建议先做 POC 验证关键链路（分片算法、ID 生成、读写分离），再逐步推广到全业务。
