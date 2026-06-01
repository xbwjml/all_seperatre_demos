# ShardingSphere 订单系统演示

基于 [`ShardingSphere订单系统技术方案.md`](./ShardingSphere订单系统技术方案.md) 落地的可运行 Demo，覆盖：

- **订单创建**（雪花基因 ID + 同分片本地事务写入主表 + 明细表）
- **订单状态更新**（状态机白名单 + 乐观锁 + 主从延迟透明）
- **订单查询**（用户维度精确查询 / 仅凭订单号反推路由 / 用户列表游标分页）

模块完全自包含，**不依赖 `com.example.demo` 下任何其他 package**。

---

## 一、目录结构

```
orderCase/
├── README.md                          本说明
├── ShardingSphere订单系统技术方案.md     设计文档（Source of Truth）
├── algorithm/
│   ├── OrderDbShardingAlgorithm.java   按 user_id 分库 (CLASS_BASED)
│   └── OrderTblShardingAlgorithm.java  按 user_id 分表 (CLASS_BASED)
├── id/
│   └── GeneSnowflakeKeyGenerator.java  基因雪花 ID + 反向路由
├── config/
│   ├── OrderShardingProperties.java    @ConfigurationProperties
│   ├── ShardingSphereDataSourceConfig.java   动态 YAML + DataSource Bean
│   └── OrderSchemaInitializer.java     启动建库建表
├── domain/
│   ├── Order.java
│   ├── OrderItem.java
│   └── OrderStatus.java                状态机
├── dto/
│   ├── ApiResp.java
│   ├── CreateOrderRequest.java
│   ├── UpdateOrderStatusRequest.java
│   └── OrderResponse.java
├── exception/
│   └── OrderException.java
├── mapper/
│   ├── OrderMapper.java                MyBatis Mapper 接口
│   └── OrderItemMapper.java
├── service/
│   └── OrderService.java               核心业务
└── controller/
    └── ShardingOrderController.java    REST API

resources/mapper/order-sharding/
├── OrderMapper.xml                     SQL 映射
└── OrderItemMapper.xml
```

> 持久层使用 **MyBatis 3.0.4**（`mybatis-spring-boot-starter`），通过自定义 `MyBatisConfig` 把
> `SqlSessionFactory` 显式绑定到 ShardingSphere 包装过的 `orderShardingDataSource`，并用 `@MapperScan`
> 局部化扫描，避免污染其他模块。`MybatisAutoConfiguration` 已被 `application.yml` 排除。

---

## 二、运行前置

1. **MySQL 8** 实例（默认 `localhost:3306`，root / root）
2. JDK 21
3. Maven 编译：`mvn -q compile`

> 演示规模：默认 **2 库 × 4 表 = 8 张订单表 + 8 张订单明细表**（与文档生产规模 16×64=1024 算法形态完全一致，仅缩减规模便于本机演示）。

---

## 三、启用方式

修改 `src/main/resources/application.yml`：

```yaml
demo:
  order-sharding:
    enabled: true        # 开启模块
    base-url: jdbc:mysql://localhost:3306/
    schemas:
      - order_db_0
      - order_db_1
    username: root
    password: root
    table-count-per-db: 4
    init-schema: true    # 首次启动自动建库建表，第二次启动可关闭
    worker-id: 0
```

`init-schema: true` 时，启动时会：
1. 用 root 连 `localhost:3306` 创建 `order_db_0`、`order_db_1` 两个 schema；
2. 分别在每个 schema 下建 `t_order_0..3` 与 `t_order_item_0..3` 共 8 张物理表。

---

## 四、API 一览

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/order-sharding/orders` | 创建订单 |
| PUT  | `/api/order-sharding/orders/{orderId}/status` | 更新状态（状态机+乐观锁） |
| GET  | `/api/order-sharding/orders/{orderId}?userId=xxx` | 用户维度精确查询（最优路径） |
| GET  | `/api/order-sharding/orders/{orderId}/by-id` | 仅凭订单号查询（主表广播归并 + 基因定位分片） |
| GET  | `/api/order-sharding/users/{userId}/orders?lastOrderId=&size=20` | 用户订单列表（游标分页） |

---

## 五、curl 示例

### 1. 创建订单

```bash
curl -X POST http://localhost:8080/api/order-sharding/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": 100086,
    "sellerId": 9001,
    "payType": 1,
    "remark": "iPhone 15 Pro",
    "items": [
      {"skuId": 5001, "spuId": 9001, "skuName": "iPhone 15 Pro 256G", "price": 8999.00, "quantity": 1},
      {"skuId": 5002, "spuId": 9001, "skuName": "保护壳",          "price":   99.00, "quantity": 2}
    ]
  }'
```

返回里 `data.shardDbIndex` / `data.shardTableIndex` 显示该订单实际落入的物理分片，便于直观验证基因法路由。

### 2. 查询（用户维度，最优路径）

```bash
curl 'http://localhost:8080/api/order-sharding/orders/1234567890?userId=100086'
```

### 3. 查询（仅凭订单号）

```bash
curl 'http://localhost:8080/api/order-sharding/orders/1234567890/by-id'
```

实现原理：当前 `t_order` 用 standard 策略按 `user_id` 分片。仅持有 `order_id` 时，主表以 `WHERE order_id = ?` 广播归并（`order_id` 唯一，至多命中一行）；拿到订单后用其**真实 `user_id`** 精确查明细（命中单分片）。

> 基因法（`orderId & 0x3FF`）在这里用于**反推订单所在物理分片**（见返回的 `shardDbIndex` / `shardTableIndex`），便于排查与定向运维；它**不能**当作 `WHERE user_id = ?` 的过滤值——那是个常见陷阱，因为基因只是真实 `user_id` 的低 10 位。若要彻底避免广播，需要把表改成 Hint 分片策略并用 `HintManager` 强制路由。

### 4. 列表（游标分页）

```bash
# 首页
curl 'http://localhost:8080/api/order-sharding/users/100086/orders?size=10'
# 翻页：把上一页最后一条 orderId 当游标
curl 'http://localhost:8080/api/order-sharding/users/100086/orders?lastOrderId=1234567890&size=10'
```

### 5. 状态更新（状态机：待支付 → 已支付）

```bash
curl -X PUT http://localhost:8080/api/order-sharding/orders/1234567890/status \
  -H 'Content-Type: application/json' \
  -d '{"userId": 100086, "targetStatus": 1, "expectedVersion": 0}'
```

状态码：`0=待支付  1=已支付  2=已发货  3=已完成  4=已取消  5=退款中`

非法状态迁移会返回 `409`，乐观锁版本不一致也返回 `409`。

---

## 六、关键设计点对照

| 设计点 | 实现位置 |
|--------|---------|
| user_id 分库分表算法（二次取模） | `OrderDbShardingAlgorithm` / `OrderTblShardingAlgorithm` |
| 基因雪花 ID（1+41+4+8+10=64 bit） | `GeneSnowflakeKeyGenerator` |
| 时钟回拨保护 | 同上类 `nextId()` 内 |
| workerId 范围校验 | 同上类构造器 |
| 订单 + 明细绑定表（同分片本地事务） | YAML `bindingTables: t_order,t_order_item` |
| ShardingSphere 5.5.x 原生 YAML 配置 | `ShardingSphereDataSourceConfig#buildYaml` |
| `YamlShardingSphereDataSourceFactory` 构造数据源 | 同上类 |
| 用户维度精确查询（带分片键） | `OrderMapper#selectByUserAndOrderId` |
| 仅凭 orderId 查询（广播归并 + 基因定位分片） | `OrderService#getByOrderId` + `idGen.geneOf` |
| 游标分页（用 order_id 而非物理 id） | `OrderMapper#listByUser` |
| 状态机白名单 | `OrderStatus#allowedNext` |
| 乐观锁更新 | `OrderMapper#updateStatus` (`version=? AND status=?`) |
| 订单/明细持久层 | MyBatis (Mapper 接口 + XML，绑定表保证主子同分片) |

---

## 七、关闭模块

```yaml
demo:
  order-sharding:
    enabled: false
```

所有 Bean (`@ConditionalOnProperty`) 不创建，与其他演示模块互不影响。
