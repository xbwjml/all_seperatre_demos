package com.example.demo.localMsgTable.payStock.mq;

import com.example.demo.localMsgTable.payStock.enums.OrderStatus;
import com.example.demo.localMsgTable.payStock.repository.PayOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 库存扣减消费者（模拟库存服务）。
 * 实际生产中此消费者运行在库存服务进程中，这里为了演示放在同一项目。
 * 扣减成功后回写订单状态为 STOCK_DEDUCTED。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = StockMessageProducer.TOPIC,
        selectorExpression = "DEDUCT_STOCK",
        consumerGroup = "stock-deduct-consumer-group"
)
public class StockDeductConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayOrderRepository orderRepo;

    @Override
    public void onMessage(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String orderId = node.get("orderId").asText();
            String skuId = node.get("skuId").asText();
            int quantity = node.get("quantity").asInt();

            // 幂等: CAS 更新，只有 PAID 状态才能变为 STOCK_DEDUCTED，重复消费时 updated=0 直接跳过
            int updated = orderRepo.updateStatus(orderId, OrderStatus.STOCK_DEDUCTED, OrderStatus.PAID);
            if (updated == 0) {
                log.info("[StockDeduct] Already processed or invalid state, skipping: orderId={}", orderId);
                return;
            }

            log.info("[StockDeduct] Deducting stock: orderId={}, skuId={}, quantity={}", orderId, skuId, quantity);
            log.info("[StockDeduct] Stock deducted & order status updated to STOCK_DEDUCTED: orderId={}", orderId);
        } catch (Exception e) {
            log.error("[StockDeduct] Failed to process message: {}", payload, e);
            throw new RuntimeException("Stock deduction failed", e);
        }
    }
}
