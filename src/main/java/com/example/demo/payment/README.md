# Payment 支付模块

基于 Spring Boot 实现的支付系统，支持多渠道支付（支付宝、微信支付、余额支付）、退款（全额/部分）、订单关闭及幂等性保障。

---

## 目录结构

```
payment/
├── README.md
├── PaymentDataInitializer.java          集成测试入口（开关控制）
├── channel/                             渠道抽象层
│   ├── PaymentChannelGateway.java       渠道网关统一接口
│   ├── PaymentChannelRequest.java       支付请求
│   ├── PaymentChannelResult.java        渠道调用结果（支付/退款通用）
│   ├── RefundChannelRequest.java        退款请求
│   ├── ChannelRouter.java               渠道路由器
│   └── mock/                            Mock 网关实现
│       ├── AlipayGateway.java
│       ├── WechatPayGateway.java
│       └── BalanceGateway.java
├── common/
│   ├── ApiResponse.java                 统一响应体
│   └── GlobalExceptionHandler.java      全局异常处理
├── controller/
│   ├── PaymentController.java
│   └── RefundController.java
├── domain/
│   ├── PaymentChannel.java              渠道枚举
│   ├── PaymentOrder.java                支付订单
│   ├── PaymentStatus.java               支付状态枚举
│   ├── Refund.java                      退款单
│   └── RefundStatus.java                退款状态枚举
├── dto/
│   ├── CreatePaymentRequest.java
│   ├── PaymentResult.java
│   ├── CreateRefundRequest.java
│   └── RefundResult.java
├── exception/
│   ├── PaymentException.java
│   ├── PaymentOrderNotFoundException.java
│   └── RefundException.java
├── repository/
│   ├── PaymentOrderRepository.java
│   └── RefundRepository.java
└── service/
    ├── PaymentService.java
    └── RefundService.java
```

---

## REST API

### 支付接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/payments` | 发起支付（幂等） |
| `GET` | `/api/payments/{outTradeNo}` | 查询支付状态 |
| `POST` | `/api/payments/{outTradeNo}/close` | 关闭订单（仅 PENDING/PAYING 可关闭） |

### 退款接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/refunds` | 发起退款（幂等，支持部分退款） |
| `GET` | `/api/refunds/{outRefundNo}` | 查询退款单 |
| `GET` | `/api/refunds/trade/{outTradeNo}` | 查询某支付订单的所有退款记录 |

---

## 请求 / 响应示例

### 发起支付

```http
POST /api/payments
Content-Type: application/json

{
  "outTradeNo": "PAY-20260404-001",
  "userId": "user-001",
  "amount": 200.00,
  "channel": "ALIPAY",
  "subject": "购买商品A"
}
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "outTradeNo": "PAY-20260404-001",
    "channelTradeNo": "ALIPAY-1743000000000-12345",
    "userId": "user-001",
    "amount": 200.00,
    "refundedAmount": 0,
    "channel": "ALIPAY",
    "status": "SUCCESS",
    "subject": "购买商品A",
    "paidAt": "2026-04-04T10:00:00"
  }
}
```

### 发起退款（部分退款）

```http
POST /api/refunds
Content-Type: application/json

{
  "outRefundNo": "REFUND-20260404-001",
  "outTradeNo":  "PAY-20260404-001",
  "refundAmount": 50.00,
  "reason": "质量问题"
}
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "outRefundNo": "REFUND-20260404-001",
    "outTradeNo": "PAY-20260404-001",
    "channelRefundNo": "ALIPAY-REFUND-1743000000001",
    "refundAmount": 50.00,
    "status": "SUCCESS",
    "refundedAt": "2026-04-04T10:05:00"
  }
}
```

### 错误响应（退款超额）

```json
{
  "code": 400,
  "message": "退款金额超出可退金额，可退: 150.00，申请退款: 999.00",
  "data": null
}
```

---

## 支持的支付渠道

| 枚举值 | 显示名 | Mock 实现 | 生产替换 |
|--------|--------|----------|---------|
| `ALIPAY` | 支付宝 | `AlipayGateway` | alipay-sdk-java |
| `WECHAT_PAY` | 微信支付 | `WechatPayGateway` | wechatpay-java |
| `BALANCE` | 余额支付 | `BalanceGateway` | 对接内部账户系统 |

---

## 核心设计

### 1. 状态机

**支付单状态**：
```
PENDING（待支付）
  └→ PAYING（支付中）
       ├→ SUCCESS（成功）
       │    └→ REFUNDED（全额退款后）
       └→ FAILED（失败）
  └→ CLOSED（主动关单）
```

**退款单状态**：
```
PENDING → PROCESSING → SUCCESS
                     → FAILED
```

状态**单向流转，禁止逆向**。

---

### 2. 幂等性

```
客户端生成唯一 outTradeNo（推荐 UUID）
  → 首次请求：ConcurrentHashMap.putIfAbsent 插入成功 → 执行支付
  → 重复请求：已存在 → 直接返回首次结果，不重复扣款
```

退款同理，使用 `outRefundNo` 作为幂等键。

---

### 3. 渠道解耦（策略模式）

```
PaymentService
  └→ ChannelRouter.route(channel)
       └→ PaymentChannelGateway（接口）
            ├→ AlipayGateway
            ├→ WechatPayGateway
            └→ BalanceGateway
```

新增渠道只需：实现 `PaymentChannelGateway` 接口 + 在 `ChannelRouter` 注册，无需修改业务逻辑。

---

### 4. 部分退款 & 全额退款检测

- `PaymentOrder.refundedAmount`：追踪累计退款金额
- 每次退款前校验：`refundAmount ≤ amount - refundedAmount`
- 退款成功后：
  - `refundedAmount < amount` → 支付单保持 `SUCCESS`（还可继续退款）
  - `refundedAmount = amount` → 支付单自动流转为 `REFUNDED`

---

### 5. 参数校验规则

**支付请求**

| 字段 | 规则 |
|------|------|
| `outTradeNo` | 非空，全局唯一 |
| `userId` | 非空 |
| `amount` | > 0，最多两位小数 |
| `channel` | 非空，枚举值 |
| `subject` | 非空 |

**退款请求**

| 字段 | 规则 |
|------|------|
| `outRefundNo` | 非空，全局唯一 |
| `outTradeNo` | 非空，对应支付单必须为 SUCCESS 状态 |
| `refundAmount` | > 0，最多两位小数，不超过可退金额 |

---

## 异常处理

| 异常类 | HTTP 状态码 | 触发场景 |
|--------|------------|---------|
| `PaymentOrderNotFoundException` | 404 | 订单不存在 |
| `RefundException` | 400 | 退款金额超额、支付单状态不对 |
| `PaymentException` | 400 | 参数校验失败、不支持的渠道 |
| `Exception` | 500 | 未预期的系统错误 |

---

## 启动与测试

### 开启集成测试

```yaml
# application.yml
demo:
  payment:
    test-initializer:
      enabled: true
```

### 测试用例（PaymentDataInitializer）

| # | 接口 | 场景 | 期望 |
|---|------|------|------|
| 1 | `POST /api/payments` | 支付宝支付 200 元 | ✅ SUCCESS |
| 2 | `POST /api/payments` | 微信支付 350 元 | ✅ SUCCESS |
| 3 | `POST /api/payments` | 余额支付 500 元 | ✅ SUCCESS |
| 4 | `GET /api/payments/{id}` | 查询支付宝订单 | ✅ 200 |
| 5 | `POST /api/refunds` | 部分退款 50 元（总额 200）| ✅ SUCCESS，支付单仍 SUCCESS |
| 6 | `POST /api/refunds` | 全额退款 350 元 | ✅ SUCCESS，支付单变 REFUNDED |
| 7 | `GET /api/refunds/trade/{id}` | 查询退款列表 | ✅ 返回退款记录 |
| 8 | `POST /api/payments/{id}/close` | 关闭已 SUCCESS 订单 | ❌ 400 状态不合法 |
| 9 | `POST /api/payments` | 金额为负数 | ❌ 400 |
| 10 | `POST /api/refunds` | 退款 9999（余额 500）| ❌ 400 超额 |
| 11 | `POST /api/refunds` | 对不存在订单退款 | ❌ 404 |
| 12 | `GET /api/payments/NOT-EXIST` | 查询不存在订单 | ❌ 404 |
| 13 | `POST /api/payments` | 重复 outTradeNo | ✅ 幂等返回首次结果 |

---

## 生产环境改造点

| 当前实现 | 生产替换方案 |
|---------|------------|
| 内存存储（ConcurrentHashMap）| MySQL + JPA/MyBatis |
| Mock 渠道网关 | 接入真实 SDK（支付宝/微信/银联）|
| 同步 Mock 支付 | 异步回调：创建订单 → 返回支付 URL → 渠道回调通知 |
| 本地内存幂等 | 数据库唯一索引 + Redis 防重 |
| 无超时机制 | 定时任务扫描超时 PENDING 订单自动关单 |
| 无对账 | 每日定时拉取渠道账单核对 |
