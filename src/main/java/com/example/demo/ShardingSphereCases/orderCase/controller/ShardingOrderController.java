package com.example.demo.ShardingSphereCases.orderCase.controller;

import com.example.demo.ShardingSphereCases.orderCase.dto.ApiResp;
import com.example.demo.ShardingSphereCases.orderCase.dto.CreateOrderRequest;
import com.example.demo.ShardingSphereCases.orderCase.dto.OrderResponse;
import com.example.demo.ShardingSphereCases.orderCase.dto.UpdateOrderStatusRequest;
import com.example.demo.ShardingSphereCases.orderCase.exception.OrderException;
import com.example.demo.ShardingSphereCases.orderCase.service.OrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单分库分表 REST API:
 * <pre>
 * POST   /api/order-sharding/orders                                  创建订单
 * PUT    /api/order-sharding/orders/{orderId}/status                 更新订单状态(状态机+乐观锁)
 * GET    /api/order-sharding/orders/{orderId}?userId=xxx             用户维度精确查询(最优)
 * GET    /api/order-sharding/orders/{orderId}/by-id                  仅凭 orderId 查询(广播归并+基因定位分片)
 * GET    /api/order-sharding/users/{userId}/orders?lastOrderId=&amp;size=  用户订单列表(游标分页)
 * </pre>
 *
 * <p>类名带 Sharding 前缀, 与 {@code com.example.demo.skywalking.OrderController} 区分,
 * 避免 Spring 默认 bean 名(orderController)冲突。</p>
 */
@RestController
@RequestMapping("/api/order-sharding")
@ConditionalOnProperty(prefix = "demo.order-sharding", name = "enabled", havingValue = "true")
public class ShardingOrderController {

    private final OrderService orderService;

    public ShardingOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    public ApiResp<OrderResponse> create(@RequestBody CreateOrderRequest req) {
        try {
            return ApiResp.ok(orderService.createOrder(req));
        } catch (OrderException e) {
            return ApiResp.error(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/orders/{orderId}/status")
    public ApiResp<OrderResponse> updateStatus(@PathVariable long orderId,
                                               @RequestBody UpdateOrderStatusRequest req) {
        try {
            return ApiResp.ok(orderService.updateStatus(orderId, req));
        } catch (OrderException e) {
            return ApiResp.error(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/orders/{orderId}")
    public ApiResp<OrderResponse> getByUserAndOrderId(@PathVariable long orderId,
                                                      @RequestParam long userId) {
        try {
            return ApiResp.ok(orderService.getByUserAndOrderId(userId, orderId));
        } catch (OrderException e) {
            return ApiResp.error(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/orders/{orderId}/by-id")
    public ApiResp<OrderResponse> getByOrderIdOnly(@PathVariable long orderId) {
        try {
            return ApiResp.ok(orderService.getByOrderId(orderId));
        } catch (OrderException e) {
            return ApiResp.error(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/users/{userId}/orders")
    public ApiResp<List<OrderResponse>> listByUser(@PathVariable long userId,
                                                   @RequestParam(required = false) Long lastOrderId,
                                                   @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResp.ok(orderService.listByUser(userId, lastOrderId, size));
    }
}
