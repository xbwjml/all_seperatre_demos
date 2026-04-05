# Transfer 转账模块

基于 Spring Boot 实现的站内转账功能，覆盖幂等性、防死锁、复式记账、状态机等核心设计。

---

## 目录结构

```
transfer/
├── README.md
├── TransferDataInitializer.java      集成测试入口（启动时自动运行，需开启配置）
├── common/
│   ├── ApiResponse.java              统一响应体
│   └── GlobalExceptionHandler.java   全局异常处理
├── controller/
│   ├── AccountController.java        账户 REST 接口
│   └── TransferController.java       转账 REST 接口
├── domain/
│   ├── Account.java                  账户领域对象
│   ├── Ledger.java                   账务流水（复式记账）
│   ├── TransferOrder.java            转账单
│   └── TransferStatus.java           转账状态枚举
├── dto/
│   ├── CreateAccountRequest.java
│   ├── DepositRequest.java
│   ├── TransferRequest.java
│   └── TransferResult.java
├── exception/
│   ├── AccountNotFoundException.java
│   ├── InsufficientBalanceException.java
│   └── TransferException.java
├── repository/                        内存实现（ConcurrentHashMap，可替换为 JPA）
│   ├── AccountRepository.java
│   ├── LedgerRepository.java
│   └── TransferOrderRepository.java
└── service/
    ├── AccountService.java
    └── TransferService.java           转账核心逻辑
```

---

## REST API

### 账户接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/accounts` | 创建账户 |
| `GET` | `/api/accounts/{accountId}` | 查询账户信息 |
| `POST` | `/api/accounts/{accountId}/deposit` | 充值（仅测试用） |
| `GET` | `/api/accounts/{accountId}/ledger` | 查询账务流水 |

### 转账接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/transfers` | 发起转账（幂等） |
| `GET` | `/api/transfers/{transferNo}` | 查询转账状态 |

---

## 请求 / 响应示例

### 创建账户

```http
POST /api/accounts
Content-Type: application/json

{
  "userId": "user-alice",
  "name": "Alice",
  "initialBalance": 10000.00
}
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "a3f1b2c4d5e6f708",
    "userId": "user-alice",
    "name": "Alice",
    "balance": 10000.00,
    "frozen": 0,
    "version": 0,
    "createdAt": "2026-04-04T10:00:00"
  }
}
```

### 发起转账

```http
POST /api/transfers
Content-Type: application/json

{
  "transferNo": "TXN-20260404-001",
  "fromAccountId": "a3f1b2c4d5e6f708",
  "toAccountId":   "b7c2d3e4f5a6b709",
  "amount": 500.00,
  "remark": "还款"
}
```

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "transferNo": "TXN-20260404-001",
    "fromAccountId": "a3f1b2c4d5e6f708",
    "toAccountId": "b7c2d3e4f5a6b709",
    "amount": 500.00,
    "status": "SUCCESS",
    "remark": "还款",
    "createdAt": "2026-04-04T10:01:00"
  }
}
```

### 错误响应（余额不足）

```json
{
  "code": 400,
  "message": "账户 [a3f1b2c4d5e6f708] 余额不足，可用余额: 10000，转账金额: 99999",
  "data": null
}
```

---

## 核心设计

### 1. 幂等性

客户端在请求中携带自生成的 `transferNo`（建议 UUID），服务端以此为唯一键。

```
第一次请求 → ConcurrentHashMap.putIfAbsent 插入成功 → 执行转账
重复请求   → putIfAbsent 返回已有记录              → 直接返回首次结果，不重复扣款
```

生产环境对应：数据库 `transfer_no` 字段加唯一索引，`INSERT IGNORE` / `ON CONFLICT DO NOTHING`。

---

### 2. 防超扣 + 防死锁（双账户加锁）

转账需同时锁住转出方和转入方，但直接各自加锁会引发死锁：

```
线程1（A→B）：锁 A，等待锁 B
线程2（B→A）：锁 B，等待锁 A   ← 互相等待，死锁
```

解决方案：**按账户 ID 字典序固定加锁顺序**，无论转账方向如何，始终先锁 ID 较小的账户：

```java
// 字典序小的 ID 先加锁，保证全局顺序唯一
ReentrantLock firstLock  = getLock(id1.compareTo(id2) <= 0 ? id1 : id2);
ReentrantLock secondLock = getLock(id1.compareTo(id2) <= 0 ? id2 : id1);
```

```
线程1（A→B）：先锁 A，再锁 B   ← 顺序一致
线程2（B→A）：先锁 A，再锁 B   ← 顺序一致，不会死锁
```

---

### 3. 转账单状态机

```
PENDING（创建）
  └→ PROCESSING（执行中）
       ├→ SUCCESS（成功）
       └→ FAILED（失败）

PENDING → CANCELLED（主动取消，预留）
```

状态**单向流转，禁止逆向**，防止数据被回退篡改。

---

### 4. 复式记账

每笔成功转账写入两条流水（一借一贷），`amount` 正数为收入，负数为支出：

| accountId | transferNo | amount | balanceAfter | description |
|-----------|-----------|--------|-------------|-------------|
| Alice | TXN-001 | **-500.00** | 9500.00 | 转出至账户 Bob |
| Bob | TXN-001 | **+500.00** | 5500.00 | 从账户 Alice 转入 |

特性：
- 流水记录**只增不改**，不可删除
- `balanceAfter` 为余额快照，账户余额可从流水独立重算，便于对账审计

---

### 5. 参数校验规则

| 字段 | 规则 |
|------|------|
| `transferNo` | 非空，客户端保证全局唯一 |
| `fromAccountId` | 非空，账户必须存在 |
| `toAccountId` | 非空，账户必须存在，不能等于 `fromAccountId` |
| `amount` | 大于 0，最多两位小数 |

---

## 异常处理

| 异常类 | HTTP 状态码 | 触发场景 |
|--------|------------|---------|
| `AccountNotFoundException` | 404 | 账户不存在 |
| `InsufficientBalanceException` | 400 | 可用余额不足 |
| `TransferException` | 400 | 参数校验失败、自转账、转账执行异常 |
| `Exception` | 500 | 未预期的系统错误 |

---

## 启动与测试

### 开启集成测试

在 `application.yml` 中将开关设为 `true`：

```yaml
demo:
  transfer:
    test-initializer:
      enabled: true
```

或启动时通过命令行参数覆盖：

```bash
java -jar app.jar --demo.transfer.test-initializer.enabled=true
```

### 测试用例（TransferDataInitializer）

开关开启后，应用启动时自动执行以下 10 个用例：

| # | 接口 | 场景 | 期望结果 |
|---|------|------|---------|
| 1 | `POST /api/accounts` | 创建 Alice（余额 10000）| 200 |
| 2 | `POST /api/accounts` | 创建 Bob（余额 5000） | 200 |
| 3 | `GET /api/accounts/{id}` | 查询 Alice 账户信息 | 200 |
| 4 | `POST /api/accounts/{id}/deposit` | Alice 充值 500 | 200，余额变为 10500 |
| 5 | `GET /api/accounts/{id}` | 验证 Alice 余额已更新 | 200，balance=10500 |
| 6 | `GET /api/accounts/{id}/ledger` | 查询 Alice 流水（无转账）| 200，data=[] |
| 7 | `GET /api/accounts/nonexistent` | 查询不存在的账户 | **404** |
| 8 | `POST /api/accounts/{id}/deposit` | 充值负数 -100 | **400** |
| 9 | `POST /api/accounts` | userId 为空 | **400** |
| 10 | `POST /api/transfers` | 转出 99999（超出余额 10500）| **400** 余额不足 |

---

## 生产环境改造点

当前实现使用内存存储，替换为生产环境只需改动 Repository 层：

| 当前实现 | 生产替换方案 |
|---------|------------|
| `ConcurrentHashMap` | MySQL / PostgreSQL + JPA |
| `putIfAbsent` 幂等 | `transfer_no` 唯一索引 + `INSERT IGNORE` |
| `ReentrantLock` | Redis 分布式锁（Redisson）/ 数据库行锁 |
| 内存流水列表 | 数据库 `ledger` 表（append-only） |
| 本地事务模拟 | `@Transactional` + 数据库事务 |
