# Apache SkyWalking 集成指南

## 项目结构

```
all_seperatre_demos/
├── src/main/java/com/example/demo/
│   └── skywalking/
│       └── OrderController.java      # 演示链路追踪的 REST 接口
├── skywalking-agent/
│   └── config/
│       └── agent.config              # Agent 核心配置
├── docker-compose.yml                # SkyWalking 完整监控栈
├── start-with-skywalking.sh          # 一键启动脚本
└── pom.xml                           # 已添加 apm-toolkit-trace 依赖
```

---

## 快速启动

### 第一步：启动 SkyWalking 监控栈

```bash
docker-compose up -d
```

等待约 1 分钟，容器全部就绪后访问：

| 服务 | 地址 |
|------|------|
| SkyWalking UI | http://localhost:8088 |
| OAP gRPC（Agent 上报） | localhost:11800 |
| Elasticsearch | http://localhost:9200 |

### 第二步：启动 Spring Boot 应用（带 Agent）

```bash
./start-with-skywalking.sh
```

脚本会自动下载 Agent（首次运行）、构建项目、并挂载 Agent 启动应用。

> 如果已有 skywalking-agent.jar，也可手动执行：
> ```bash
> java -javaagent:skywalking-agent/skywalking-agent.jar \
>      -Dskywalking.agent.service_name=demo-service \
>      -Dskywalking.collector.backend_service=127.0.0.1:11800 \
>      -jar target/demo-0.0.1-SNAPSHOT.jar
> ```

---

## 测试接口

应用启动后，使用以下命令触发追踪数据：

### 查询订单（正常链路）
```bash
curl http://localhost:8080/api/orders/ORD-12345
```

### 创建订单（正常）
```bash
curl -X POST http://localhost:8080/api/orders \
     -H "Content-Type: application/json" \
     -d '{"product": "iPhone 16", "quantity": 1}'
```

### 创建订单（模拟异常，链路标红）
```bash
curl -X POST "http://localhost:8080/api/orders?failMode=true" \
     -H "Content-Type: application/json" \
     -d '{"product": "iPhone 16", "quantity": 1}'
```

---

## 在 SkyWalking UI 中查看结果

1. 打开 http://localhost:8088
2. 左侧菜单选择 **Trace** → 可按服务名 `demo-service` 过滤
3. 点击任意一条 Trace，展开 Span 树，可以看到：

```
[Entry Span] GET /api/orders/{orderId}
  └── [Local Span] buildOrderDetail
        └── 耗时、tag (orderId)、自定义 log

[Entry Span] POST /api/orders
  ├── [Local Span] validateOrder
  ├── [Local Span] persistOrder
  └── [Local Span] simulateDownstreamFailure  ← 异常时标红 ❌
```

4. 左侧菜单选择 **Topology** → 查看服务拓扑图
5. 左侧菜单选择 **Dashboard** → 查看 QPS、响应时间、错误率等指标

---

## 核心 API 说明

### @Trace 注解

```java
@Trace(operationName = "myMethod")
public void myMethod() {
    // 该方法自动产生一个 Local Span，无需任何其他代码
}
```

### TraceContext（获取 TraceId）

```java
// 将 traceId 写入日志，方便与 SkyWalking UI 的链路对照
String traceId = TraceContext.traceId();
log.info("traceId={}", traceId);
```

### ActiveSpan（手动埋点）

```java
// 添加业务 tag
ActiveSpan.tag("user.id", userId);

// 标记当前 Span 为错误状态
ActiveSpan.error("支付超时");
ActiveSpan.error(exception);
```

---

## 日志关联 TraceId（Logback）

在 `src/main/resources/logback-spring.xml` 中使用 SkyWalking 提供的 `%tid` 占位符，
日志将自动打印 TraceId，方便日志与链路关联：

```xml
<encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
    <layout class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdPatternLogbackLayout">
        <pattern>%d{yyyy-MM-dd HH:mm:ss} [%tid] %-5level %logger{36} - %msg%n</pattern>
    </layout>
</encoder>
```

---

## 停止服务

```bash
docker-compose down        # 停止并保留数据
docker-compose down -v     # 停止并清除所有数据
```
