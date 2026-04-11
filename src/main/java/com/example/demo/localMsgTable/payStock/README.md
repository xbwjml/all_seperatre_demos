# 支付到扣减库存 -- 本地消息表 + RocketMQ 方案

## 概述

以电商场景为例，演示**支付服务**与**库存服务**之间的分布式最终一致性方案。

核心模式：本地消息表 + RocketMQ 普通消息 + 定时补偿。

## 技术栈

| 组件 | 版本/说明 |
|---|---|
| Spring Boot | 3.3.8 |
| Java | 21 |
| RocketMQ | rocketmq-spring-boot-starter 2.3.5 |
| 数据库 | MySQL（JdbcTemplate） |
| 缓存 | Redis（分布式锁） |

## 启用方式

在 `application.yml` 中配置：

```yaml
demo:
  pay-stock:
    enabled: true
    mysql:
      url: jdbc:mysql://localhost:3306/pay_stock_db?useSSL=false&serverTimezone=Asia/Shanghai
      username: root
      password: root
      driver-class-name: com.mysql.cj.jdbc.Driver

rocketmq:
  name-server: localhost:9876
  producer:
    group: pay-stock-producer-group
```

启动时 `SchemaInitializer` 会自动创建 `pay_order`、`local_message`、`refund_record` 三张表。

## 包结构

```
payStock/
├── config/
│   ├── PayStockDataSourceConfig.java   独立数据源 + 事务管理器 + @EnableScheduling
│   └── SchemaInitializer.java          应用启动时自动建表
├── enums/
│   ├── MsgStatus.java                  消息状态: PENDING -> SENT -> DEAD
│   ├── MsgType.java                    消息类型: DEDUCT_STOCK / RESTORE_STOCK
│   ├── OrderStatus.java                订单状态: UNPAID -> PAID -> STOCK_DEDUCTED -> REFUNDED
│   ├── PayChannel.java                 支付渠道: ALIPAY / WECHAT
│   └── RefundStatus.java               退款状态: PENDING / SUCCESS / FAILED
├── domain/
│   ├── PayOrder.java                   订单实体
│   ├── LocalMessage.java               本地消息实体
│   └── RefundRecord.java               退款记录实体
├── repository/
│   ├── PayOrderRepository.java         订单数据访问
│   ├── LocalMessageRepository.java     消息数据访问
│   └── RefundRecordRepository.java     退款记录数据访问
├── channel/
│   ├── PayChannelGateway.java          支付渠道接口（定义 pay/refund）
│   ├── AlipayChannelGateway.java       支付宝 Mock 实现
│   ├── WechatChannelGateway.java       微信支付 Mock 实现
│   └── PayChannelRouter.java           渠道路由器（按 PayChannel 枚举分发）
├── mq/
│   ├── StockMessageProducer.java       RocketMQ 普通消息生产者
│   ├── StockDeductConsumer.java        库存扣减消费者（tag: DEDUCT_STOCK）
│   └── StockRestoreConsumer.java       库存回滚消费者（tag: RESTORE_STOCK）
├── service/
│   └── PayStockPaymentAppService.java   核心业务：支付 / 退款
├── scheduler/
│   └── MsgCompensateScheduler.java     PENDING 消息补偿重发（Redis 分布式锁）
└── controller/
    └── PayOrderController.java         REST 接口
```

## API 接口

### 创建订单

```
POST /pay-stock/orders
Content-Type: application/json

{
  "skuId": "SKU_001",
  "quantity": 2,
  "amount": 199.00,
  "channel": "ALIPAY"
}
```

### 支付

```
POST /pay-stock/orders/{orderId}/pay
```

### 退款

```
POST /pay-stock/orders/{orderId}/refund
```

## 核心流程

### 正向：支付 -> 扣库存

```
用户发起支付
  │
  ▼
PayStockPaymentAppService.pay()
  ├── 1. 调用渠道网关扣款（Alipay/Wechat Mock）
  ├── 2. 本地事务（同一 @Transactional）：
  │     ├── 更新订单 UNPAID -> PAID
  │     └── 写入本地消息 DEDUCT_STOCK (PENDING)
  ├── 3. 事务提交后立即发送 RocketMQ 普通消息
  │     ├── 成功 → 消息状态 PENDING -> SENT
  │     └── 失败 → 消息留在 PENDING，由补偿任务兜底
  │
  ▼
StockDeductConsumer 消费消息
  ├── CAS 更新订单 PAID -> STOCK_DEDUCTED（天然幂等）
  └── 执行库存扣减
```

### 异常补偿：渠道扣款成功但本地事务失败

```
渠道扣款成功 → 本地事务抛异常
  │
  ▼
catch 块自动调用 gateway.refund() 补偿退款
  ├── 补偿退款成功 → 用户资金已退，无副作用
  └── 补偿退款也失败 → 记录日志，需人工介入
```

### 反向：退款 -> 回滚库存

```
用户发起退款
  │
  ▼
PayStockPaymentAppService.refund()
  ├── 1. 调用渠道网关退款
  ├── 2. 本地事务：
  │     ├── 更新订单 -> REFUNDED
  │     ├── 写入退款记录
  │     └── 写入本地消息 RESTORE_STOCK (PENDING)
  ├── 3. 发送 RocketMQ 消息（同支付流程）
  │
  ▼
StockRestoreConsumer 消费消息
  └── 执行库存回滚
```

### 异常补偿：渠道退款成功但本地事务失败

```
渠道退款成功 → 本地事务抛异常
  │
  ▼
catch 块标记订单为 REFUNDING（钱已退但状态未完整更新）
  └── 后续由对账任务或人工根据 REFUNDING 状态兜底处理
```

### 补偿任务

```
MsgCompensateScheduler（每 10 秒执行）
  │
  ├── Redis 分布式锁防止多实例重复扫描
  │
  ├── 查询 status=PENDING 且 nextRetryTime<=now 的消息
  │
  ├── 重新发送到 RocketMQ
  │     ├── 成功 → PENDING -> SENT
  │     └── 失败 → retryCount++, nextRetryTime 指数退避
  │
  └── retryCount >= maxRetry → 标记 DEAD（需人工介入）
```

## 订单状态流转

```
UNPAID ──支付──> PAID ──消费者扣库存──> STOCK_DEDUCTED
                  │                         │
                  └──退款──> REFUNDED <──退款──┘
                               │
              (事务异常) REFUNDING (需人工对账)

              (支付事务异常) CANCELLED (渠道已补偿退款)
```

## 消息状态流转

```
PENDING ──发送成功──> SENT
    │
    └──补偿重试失败──> retryCount++ (指数退避)
                         │
                         └──超过 maxRetry──> DEAD (人工介入)
```

## 关键设计要点

1. **原子性**：订单状态变更与本地消息写入在同一个数据库事务中，保证要么都成功要么都不成功。

2. **最终一致性**：事务提交后立即尝试发 MQ；即使发送失败，补偿任务会持续重试直到成功（或标记 DEAD）。

3. **幂等消费**：`StockDeductConsumer` 使用 CAS 更新（`WHERE status = 'PAID'`），重复消费时 updated=0 直接跳过。

4. **多渠道**：`PayChannelGateway` 接口 + 策略路由，新增渠道只需实现接口并注册为 Spring Bean。

5. **异常补偿**：渠道调用成功但本地事务失败时，支付场景自动发起渠道退款，退款场景标记 REFUNDING 等待人工对账。

6. **分布式锁**：补偿任务通过 Redis `SETNX` 抢锁，防止多实例部署时重复扫描。

7. **指数退避**：补偿重试间隔为 `2^retryCount * 10秒`（10s, 20s, 40s, 80s, 160s），避免频繁重试。

8. **模块隔离**：所有 Bean 均通过 `@ConditionalOnProperty(name = "demo.pay-stock.enabled")` 控制，不影响项目其他模块。
