package com.example.demo.skywalking;

import org.apache.skywalking.apm.toolkit.trace.ActiveSpan;
import org.apache.skywalking.apm.toolkit.trace.Tag;
import org.apache.skywalking.apm.toolkit.trace.Tags;
import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.apache.skywalking.apm.toolkit.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 演示 SkyWalking 链路追踪的 REST Controller。
 *
 * 主要展示：
 *   1. @Trace          - 自动为方法创建子 Span
 *   2. TraceContext     - 在代码中获取当前 TraceId
 *   3. ActiveSpan      - 手动为当前 Span 添加 tag / log
 *   4. 异常捕获         - 错误自动标记到链路
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    /**
     * 查询订单接口。
     * HTTP 请求进入后，SkyWalking Java Agent 自动创建一条 Entry Span；
     * queryOrder() 内部调用 buildOrderDetail()，Agent 通过 @Trace 再创建一条 Local Span，
     * 最终在 SkyWalking UI 中可以看到完整的 Span 树。
     */
    @GetMapping("/{orderId}")
    public Map<String, Object> queryOrder(@PathVariable String orderId) {
        log.info("[SkyWalking] traceId={} | 开始处理订单查询, orderId={}", TraceContext.traceId(), orderId);

        Map<String, Object> detail = buildOrderDetail(orderId);

        // 在当前 Span 上追加业务 tag，方便在 SkyWalking UI 过滤
        ActiveSpan.tag("order.id", orderId);
        ActiveSpan.tag("order.status", (String) detail.get("status"));

        log.info("[SkyWalking] traceId={} | 订单查询完成", TraceContext.traceId());
        return detail;
    }

    /**
     * 创建订单接口（模拟慢查询 + 异常场景）。
     *
     * @param failMode 传 true 时模拟下游服务异常，链路会被标红
     */
    @PostMapping
    public Map<String, Object> createOrder(
            @RequestParam(defaultValue = "false") boolean failMode,
            @RequestBody Map<String, Object> body) {

        log.info("[SkyWalking] traceId={} | 创建订单请求, body={}", TraceContext.traceId(), body);

        validateOrder(body);
        if (failMode) {
            simulateDownstreamFailure();
        }
        Map<String, Object> result = persistOrder(body);

        ActiveSpan.tag("order.id", (String) result.get("orderId"));
        return result;
    }

    // ────────────────────────────────────────────────
    //  以下方法使用 @Trace 注解，每个方法都会产生独立的 Local Span
    // ────────────────────────────────────────────────

    /**
     * @Trace          - 为该方法自动创建一个名为 "buildOrderDetail" 的 Local Span
     * @Tags / @Tag    - 将方法参数/返回值自动绑定为 Span 的 tag（需 Agent 支持）
     */
    @Trace(operationName = "buildOrderDetail")
    @Tags({@Tag(key = "orderId", value = "arg[0]")})
    private Map<String, Object> buildOrderDetail(String orderId) {
        simulateLatency(50);
        Map<String, Object> detail = new HashMap<>();
        detail.put("orderId", orderId);
        detail.put("status", "PAID");
        detail.put("amount", 299.00);
        detail.put("traceId", TraceContext.traceId()); // 将 traceId 返回给前端，方便对照查询
        return detail;
    }

    @Trace(operationName = "validateOrder")
    private void validateOrder(Map<String, Object> body) {
        simulateLatency(20);
        if (body == null || body.isEmpty()) {
            // 手动在当前 Span 上记录错误日志
            ActiveSpan.error("订单参数不能为空");
            throw new IllegalArgumentException("订单参数不能为空");
        }
    }

    @Trace(operationName = "persistOrder")
    private Map<String, Object> persistOrder(Map<String, Object> body) {
        simulateLatency(80);
        Map<String, Object> result = new HashMap<>(body);
        result.put("orderId", "ORD-" + System.currentTimeMillis());
        result.put("traceId", TraceContext.traceId());
        return result;
    }

    /** 模拟下游服务异常，SkyWalking 会将该 Span 标记为 ERROR */
    @Trace(operationName = "simulateDownstreamFailure")
    private void simulateDownstreamFailure() {
        ActiveSpan.error(new RuntimeException("下游支付服务超时"));
        throw new RuntimeException("下游支付服务超时");
    }

    private void simulateLatency(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
