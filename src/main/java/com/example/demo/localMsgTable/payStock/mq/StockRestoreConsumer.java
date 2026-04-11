package com.example.demo.localMsgTable.payStock.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 库存回滚消费者（模拟库存服务）。
 * 退款成功后回滚已扣减的库存。
 * 幂等依赖订单状态：只有 REFUNDED 状态才执行回滚，且消费完不改状态，天然幂等。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = StockMessageProducer.TOPIC,
        selectorExpression = "RESTORE_STOCK",
        consumerGroup = "stock-restore-consumer-group"
)
public class StockRestoreConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String orderId = node.get("orderId").asText();
            String skuId = node.get("skuId").asText();
            int quantity = node.get("quantity").asInt();

            // 实际生产中应在库存服务做幂等判断（如库存流水表唯一索引）
            log.info("[StockRestore] Restoring stock: orderId={}, skuId={}, quantity={}", orderId, skuId, quantity);
            log.info("[StockRestore] Stock restored successfully: orderId={}", orderId);
        } catch (Exception e) {
            log.error("[StockRestore] Failed to process message: {}", payload, e);
            throw new RuntimeException("Stock restoration failed", e);
        }
    }
}
