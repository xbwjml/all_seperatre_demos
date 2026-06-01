package com.example.demo.ShardingSphereCases.orderCase.service;

import com.example.demo.ShardingSphereCases.orderCase.domain.Order;
import com.example.demo.ShardingSphereCases.orderCase.domain.OrderItem;
import com.example.demo.ShardingSphereCases.orderCase.domain.OrderStatus;
import com.example.demo.ShardingSphereCases.orderCase.dto.CreateOrderRequest;
import com.example.demo.ShardingSphereCases.orderCase.dto.OrderResponse;
import com.example.demo.ShardingSphereCases.orderCase.dto.UpdateOrderStatusRequest;
import com.example.demo.ShardingSphereCases.orderCase.exception.OrderException;
import com.example.demo.ShardingSphereCases.orderCase.id.GeneSnowflakeKeyGenerator;
import com.example.demo.ShardingSphereCases.orderCase.mapper.OrderItemMapper;
import com.example.demo.ShardingSphereCases.orderCase.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单核心服务: 创建 / 状态更新 / 查询。
 *
 * <p>事务说明: 订单主表 t_order 与 订单明细表 t_order_item 配置为绑定表,
 * 同一 user_id 必落同一分片, 因此本地事务即可保证强一致, 无需分布式事务。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "demo.order-sharding", name = "enabled", havingValue = "true")
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper itemMapper;
    private final GeneSnowflakeKeyGenerator idGen;

    public OrderService(OrderMapper orderMapper,
                        OrderItemMapper itemMapper,
                        GeneSnowflakeKeyGenerator idGen) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.idGen = idGen;
    }

    // ============================================================
    // 1. 创建订单
    // ============================================================

    @Transactional(transactionManager = "orderShardingTxManager", rollbackFor = Exception.class)
    public OrderResponse createOrder(CreateOrderRequest req) {
        validateCreate(req);

        long orderId = idGen.nextId(req.getUserId());
        LocalDateTime now = LocalDateTime.now();

        BigDecimal totalAmount = computeTotal(req);
        BigDecimal payAmount = req.getPayAmount() != null ? req.getPayAmount() : totalAmount;

        Order order = Order.builder()
                .orderId(orderId)
                .orderNo("NO" + orderId)
                .userId(req.getUserId())
                .sellerId(req.getSellerId())
                .totalAmount(totalAmount)
                .payAmount(payAmount)
                .status(OrderStatus.PENDING_PAY.getCode())
                .payType(req.getPayType())
                .createTime(now)
                .updateTime(now)
                .payTime(null)
                .remark(req.getRemark())
                .version(0)
                .build();
        orderMapper.insert(order);

        List<OrderItem> items = new ArrayList<>(req.getItems().size());
        for (CreateOrderRequest.Item it : req.getItems()) {
            items.add(OrderItem.builder()
                    .orderId(orderId)
                    .userId(req.getUserId())
                    .skuId(it.getSkuId())
                    .spuId(it.getSpuId())
                    .skuName(it.getSkuName())
                    .price(it.getPrice())
                    .quantity(it.getQuantity())
                    .createTime(now)
                    .build());
        }
        itemMapper.batchInsert(items);

        order.setItems(items);
        log.info("[order-create] orderId={}, userId={}, db={}, table={}, totalAmount={}",
                orderId, req.getUserId(),
                idGen.dbIndexOf(orderId), idGen.tableIndexOf(orderId), totalAmount);

        return OrderResponse.from(order, idGen.dbIndexOf(orderId), idGen.tableIndexOf(orderId));
    }

    private void validateCreate(CreateOrderRequest req) {
        if (req.getUserId() == null || req.getUserId() <= 0) {
            throw OrderException.badRequest("userId is required and positive");
        }
        if (req.getSellerId() == null) {
            throw OrderException.badRequest("sellerId is required");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw OrderException.badRequest("items must not be empty");
        }
        for (CreateOrderRequest.Item i : req.getItems()) {
            if (i.getSkuId() == null || i.getPrice() == null
                    || i.getQuantity() == null || i.getQuantity() <= 0) {
                throw OrderException.badRequest("item fields invalid");
            }
        }
    }

    private BigDecimal computeTotal(CreateOrderRequest req) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CreateOrderRequest.Item i : req.getItems()) {
            sum = sum.add(i.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())));
        }
        return sum;
    }

    // ============================================================
    // 2. 更新订单状态 (状态机 + 乐观锁)
    // ============================================================

    @Transactional(transactionManager = "orderShardingTxManager", rollbackFor = Exception.class)
    public OrderResponse updateStatus(long orderId, UpdateOrderStatusRequest req) {
        if (req.getUserId() == null || req.getTargetStatus() == null
                || req.getExpectedVersion() == null) {
            throw OrderException.badRequest("userId / targetStatus / expectedVersion required");
        }

        Order current = orderMapper.selectByUserAndOrderId(req.getUserId(), orderId);
        if (current == null) {
            throw OrderException.notFound(orderId);
        }

        if (current.getVersion() != req.getExpectedVersion()) {
            throw OrderException.versionConflict(orderId);
        }

        OrderStatus from = OrderStatus.of(current.getStatus());
        OrderStatus to = OrderStatus.of(req.getTargetStatus());
        if (!from.canTransitTo(to)) {
            throw OrderException.badStatusTransition(from + " -> " + to);
        }

        LocalDateTime payTime = (to == OrderStatus.PAID) ? LocalDateTime.now() : null;

        int affected = orderMapper.updateStatus(
                req.getUserId(), orderId,
                from.getCode(), to.getCode(),
                req.getExpectedVersion(), payTime);
        if (affected == 0) {
            throw OrderException.versionConflict(orderId);
        }

        log.info("[order-status] orderId={}, {} -> {}, by user={}", orderId, from, to, req.getUserId());

        return getByUserAndOrderId(req.getUserId(), orderId);
    }

    // ============================================================
    // 3. 查询
    // ============================================================

    /** 用户维度精确查询(最优路径)。 */
    @Transactional(transactionManager = "orderShardingTxManager", readOnly = true)
    public OrderResponse getByUserAndOrderId(long userId, long orderId) {
        Order order = orderMapper.selectByUserAndOrderId(userId, orderId);
        if (order == null) {
            throw OrderException.notFound(orderId);
        }
        order.setItems(itemMapper.selectByUserAndOrderId(userId, orderId));
        return OrderResponse.from(order, idGen.dbIndexOf(orderId), idGen.tableIndexOf(orderId));
    }

    /**
     * 仅凭 order_id 查询。
     *
     * <p>standard 分片策略下, 无法靠"伪造 user_id 过滤值"做单分片路由(那样会查不到真实行),
     * 因此主表查询仅以 {@code WHERE order_id = ?} 广播归并(order_id 唯一)。
     * 拿到订单后, 用其<b>真实 user_id</b> 精确路由查明细(命中单分片)。</p>
     *
     * <p>基因法在这里的价值体现在 {@code dbIndexOf}/{@code tableIndexOf}:
     * 即便只有 order_id 也能反推出它所在的物理分片, 便于排查与定向运维。</p>
     */
    @Transactional(transactionManager = "orderShardingTxManager", readOnly = true)
    public OrderResponse getByOrderId(long orderId) {
        Order order = orderMapper.selectByOrderId(orderId);
        if (order == null) {
            throw OrderException.notFound(orderId);
        }
        order.setItems(itemMapper.selectByUserAndOrderId(order.getUserId(), orderId));
        return OrderResponse.from(order, idGen.dbIndexOf(orderId), idGen.tableIndexOf(orderId));
    }

    /** 用户订单列表 + 游标分页(基于 order_id DESC)。 */
    @Transactional(transactionManager = "orderShardingTxManager", readOnly = true)
    public List<OrderResponse> listByUser(long userId, Long lastOrderId, int size) {
        long cursor = (lastOrderId == null) ? Long.MAX_VALUE : lastOrderId;
        int limit = (size <= 0 || size > 200) ? 20 : size;
        List<Order> orders = orderMapper.listByUser(userId, cursor, limit);
        List<OrderResponse> result = new ArrayList<>(orders.size());
        for (Order o : orders) {
            result.add(OrderResponse.from(
                    o, idGen.dbIndexOf(o.getOrderId()), idGen.tableIndexOf(o.getOrderId())));
        }
        return result;
    }
}
