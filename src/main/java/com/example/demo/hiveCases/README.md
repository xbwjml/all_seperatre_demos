# hiveCases —— 用户订单数据同步到 Hive（方案一：定时批量导入）

## 目录结构

```
hiveCases/
├── config/                          # 数据源配置
│   ├── MysqlDataSourceConfig.java   # MySQL 独立数据源 + JdbcTemplate
│   └── HiveDataSourceConfig.java    # Hive 独立数据源 + JdbcTemplate（HiveServer2 JDBC）
└── jobSync/                         # 定时批量同步模块
    ├── domain/
    │   └── Order.java               # 订单实体，对应 MySQL orders 表
    ├── repository/
    │   └── OrderRepository.java     # MySQL 订单查询（全量 / 增量）
    ├── service/
    │   ├── HiveSyncService.java     # Hive 写入服务（建分区 → 清旧数据 → INSERT）
    │   └── OrderSyncJob.java        # @Scheduled 定时任务（增量 + 全量两个 Cron）
    └── HiveSyncDemo.java            # 模块入口，@EnableScheduling，可选启动即触发全量
```

---

## 数据流

```
MySQL orders 表
    │
    │  OrderRepository.findUpdatedSince()   ← 增量：按 update_time 时间窗口拉取
    │  OrderRepository.findAll()            ← 全量：拉取全部订单
    ▼
List<Order>（内存）
    │
    │  HiveSyncService.syncToHive()
    │    1. ALTER TABLE ... ADD PARTITION (dt='yyyy-MM-dd')   幂等建分区
    │    2. ALTER TABLE ... DROP PARTITION (dt='yyyy-MM-dd')  清除旧数据（覆盖写）
    │    3. INSERT INTO TABLE ... PARTITION (dt='...')        逐条写入
    ▼
Hive ods.orders PARTITION(dt='yyyy-MM-dd')
```

---

## 同步策略

| 模式 | 触发方式 | 说明 |
|------|----------|------|
| **增量同步** | Cron（默认每小时整点） | 拉取上次水位线之后有变更的订单，写入当天分区 |
| **全量同步** | Cron（默认每天凌晨 1 点） | 拉取全部订单，覆盖写当天分区，适合初始化或数据修复 |
| **启动即触发** | `run-on-startup=true` | 应用启动后立即执行一次全量同步，用于调试 |

### 幂等保证

- 写入前先 `DROP PARTITION` 再 `ADD PARTITION`，重跑不会产生重复数据。
- 增量水位线只在同步**成功后**推进，失败时保持原值，下次重试覆盖同一窗口（at-least-once 语义）。

---

## 快速启用

### 1. 提前在 Hive 中建表

```sql
CREATE DATABASE IF NOT EXISTS ods;

CREATE EXTERNAL TABLE IF NOT EXISTS ods.orders (
    order_id     STRING,
    user_id      BIGINT,
    product_name STRING,
    amount       DECIMAL(10,2),
    status       STRING,
    create_time  STRING,
    update_time  STRING
)
PARTITIONED BY (dt STRING COMMENT '同步日期 yyyy-MM-dd')
STORED AS ORC
TBLPROPERTIES ('orc.compress'='SNAPPY');
```

### 2. 在 MySQL 中建表

```sql
CREATE TABLE orders (
    order_id     VARCHAR(64)   NOT NULL PRIMARY KEY,
    user_id      BIGINT        NOT NULL,
    product_name VARCHAR(256)  NOT NULL,
    amount       DECIMAL(10,2) NOT NULL,
    status       VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_update_time (update_time)
);
```

### 3. 修改 application.yml

```yaml
demo:
  hive-sync:
    enabled: true                          # 开启模块
    run-on-startup: false                  # true 则启动后立即全量同步（调试用）
    incremental-sync-cron: "0 0 * * * *"  # 增量：每小时整点
    full-sync-cron: "0 0 1 * * *"         # 全量：每天凌晨 1 点
    mysql:
      url: jdbc:mysql://localhost:3306/order_db?useSSL=false&serverTimezone=Asia/Shanghai
      username: root
      password: root
      driver-class-name: com.mysql.cj.jdbc.Driver
    hive:
      url: jdbc:hive2://localhost:10000/default
      username: hive
      password: ""
      driver-class-name: org.apache.hive.jdbc.HiveDriver
```

### 4. pom.xml 所需依赖

```xml
<!-- spring-jdbc -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-jdbc</artifactId>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>

<!-- Hive JDBC -->
<dependency>
    <groupId>org.apache.hive</groupId>
    <artifactId>hive-jdbc</artifactId>
    <version>3.1.3</version>
    <exclusions>
        <exclusion><groupId>org.eclipse.jetty</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>org.apache.logging.log4j</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-log4j12</artifactId></exclusion>
        <exclusion><groupId>log4j</groupId><artifactId>log4j</artifactId></exclusion>
        <exclusion><groupId>org.apache.tomcat</groupId><artifactId>tomcat-jasper</artifactId></exclusion>
        <exclusion><groupId>org.apache.tomcat</groupId><artifactId>tomcat-jsp-api</artifactId></exclusion>
        <exclusion><groupId>org.apache.tomcat</groupId><artifactId>tomcat-el-api</artifactId></exclusion>
        <exclusion><groupId>org.apache.tomcat</groupId><artifactId>tomcat-servlet-api</artifactId></exclusion>
        <exclusion><groupId>javax.servlet</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>javax.servlet.jsp</groupId><artifactId>*</artifactId></exclusion>
    </exclusions>
</dependency>

<!-- Hadoop Common（hive-jdbc 运行时依赖） -->
<dependency>
    <groupId>org.apache.hadoop</groupId>
    <artifactId>hadoop-common</artifactId>
    <version>3.3.6</version>
    <exclusions>
        <exclusion><groupId>org.slf4j</groupId><artifactId>slf4j-log4j12</artifactId></exclusion>
        <exclusion><groupId>log4j</groupId><artifactId>log4j</artifactId></exclusion>
        <exclusion><groupId>org.eclipse.jetty</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>javax.servlet</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>javax.servlet.jsp</groupId><artifactId>*</artifactId></exclusion>
        <exclusion><groupId>org.apache.tomcat</groupId><artifactId>tomcat-servlet-api</artifactId></exclusion>
    </exclusions>
</dependency>
```

---

## 注意事项

- **Spring Boot 3 兼容**：`hive-jdbc` 传递依赖的旧版 `javax.servlet` 与 Spring Boot 3 的 `jakarta.servlet` 冲突，需在 pom.xml 中全部排除（见上方依赖配置）。
- **DataSource 自动配置**：由于引入了 JDBC 驱动，需在 `DemoApplication` 排除 `DataSourceAutoConfiguration`，否则启动报错。
- **水位线持久化**：当前水位线存储在内存（`AtomicReference`），应用重启后会重置为昨天。生产环境建议持久化到数据库或 ZooKeeper。
- **大数据量优化**：`HiveSyncService` 当前逐条 INSERT，数据量极大时建议改为先写 HDFS 文件再执行 `LOAD DATA INPATH`。
