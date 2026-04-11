package com.example.demo.localMsgTable.payStock.mq;

import com.example.demo.localMsgTable.payStock.domain.LocalMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
@RequiredArgsConstructor
public class StockMessageProducer {

    public static final String TOPIC = "pay-stock-topic";

    private final RocketMQTemplate rocketMQTemplate;

    /**
     * 发送普通消息到 RocketMQ。
     * tag 使用 msgType（DEDUCT_STOCK / RESTORE_STOCK）区分消费者。
     *
     * @return true 发送成功，false 发送失败
     */
    public boolean send(LocalMessage localMessage) {
        String destination = TOPIC + ":" + localMessage.getMsgType().name();
        Message<String> message = MessageBuilder
                .withPayload(localMessage.getPayload())
                .setHeader("KEYS", localMessage.getId())
                .build();
        try {
            rocketMQTemplate.syncSend(destination, message);
            log.info("[PayStock] MQ message sent: id={}, type={}, orderId={}",
                    localMessage.getId(), localMessage.getMsgType(), localMessage.getOrderId());
            return true;
        } catch (Exception e) {
            log.error("[PayStock] MQ message send failed: id={}, type={}, orderId={}",
                    localMessage.getId(), localMessage.getMsgType(), localMessage.getOrderId(), e);
            return false;
        }
    }
}
