package com.example.demo.localMsgTable.payStock.service;

import com.example.demo.localMsgTable.payStock.channel.PayChannelGateway;
import com.example.demo.localMsgTable.payStock.channel.PayChannelRouter;
import com.example.demo.localMsgTable.payStock.domain.LocalMessage;
import com.example.demo.localMsgTable.payStock.domain.PayOrder;
import com.example.demo.localMsgTable.payStock.domain.RefundRecord;
import com.example.demo.localMsgTable.payStock.enums.*;
import com.example.demo.localMsgTable.payStock.mq.StockMessageProducer;
import com.example.demo.localMsgTable.payStock.repository.LocalMessageRepository;
import com.example.demo.localMsgTable.payStock.repository.PayOrderRepository;
import com.example.demo.localMsgTable.payStock.repository.RefundRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class PayStockPaymentAppService {

    private static final int COMPENSATE_DELAY_SECONDS = 15;

    private final PayOrderRepository orderRepo;
    private final LocalMessageRepository msgRepo;
    private final RefundRecordRepository refundRepo;
    private final PayChannelRouter channelRouter;
    private final StockMessageProducer messageProducer;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PayStockPaymentAppService(
            PayOrderRepository orderRepo,
            LocalMessageRepository msgRepo,
            RefundRecordRepository refundRepo,
            PayChannelRouter channelRouter,
            StockMessageProducer messageProducer,
            @Qualifier("payStockTransactionManager") PlatformTransactionManager txManager) {
        this.orderRepo = orderRepo;
        this.msgRepo = msgRepo;
        this.refundRepo = refundRepo;
        this.channelRouter = channelRouter;
        this.messageProducer = messageProducer;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * 创建订单（UNPAID 状态）。使用事务保证后续扩展（如预锁库存）的原子性。
     */
    public PayOrder createOrder(String skuId, int quantity, BigDecimal amount, PayChannel channel) {
        PayOrder order = PayOrder.builder()
                .orderId(UUID.randomUUID().toString())
                .skuId(skuId)
                .quantity(quantity)
                .amount(amount)
                .channel(channel)
                .status(OrderStatus.UNPAID)
                .build();
        txTemplate.executeWithoutResult(status -> orderRepo.insert(order));
        log.info("[PayStock] Order created: {}", order.getOrderId());
        return order;
    }

    /**
     * 支付：调渠道 -> 本地事务(订单PAID + 消息PENDING) -> 发MQ。
     * 若渠道扣款成功但本地事务失败，自动发起渠道退款补偿。
     */
    public PayOrder pay(String orderId) {
        PayOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.UNPAID) {
            throw new IllegalStateException("Order cannot be paid, current status: " + order.getStatus());
        }

        PayChannelGateway gateway = channelRouter.route(order.getChannel());
        PayChannelGateway.PayResult payResult = gateway.pay(order.getOrderId(), order.getAmount());
        if (!payResult.success()) {
            throw new RuntimeException("Channel payment failed for order: " + orderId);
        }

        String msgId = UUID.randomUUID().toString();
        String payload = buildStockPayload(order);

        LocalMessage localMessage;
        try {
            localMessage = txTemplate.execute(status -> {
                int updated = orderRepo.updateStatusAndTradeNo(
                        orderId, OrderStatus.PAID, OrderStatus.UNPAID, payResult.channelTradeNo());
                if (updated == 0) {
                    throw new IllegalStateException("Concurrent modification: order status changed");
                }

                LocalMessage msg = LocalMessage.builder()
                        .id(msgId)
                        .orderId(orderId)
                        .msgType(MsgType.DEDUCT_STOCK)
                        .payload(payload)
                        .status(MsgStatus.PENDING)
                        .nextRetryTime(LocalDateTime.now().plusSeconds(COMPENSATE_DELAY_SECONDS))
                        .build();
                msgRepo.insert(msg);
                return msg;
            });
        } catch (Exception e) {
            log.error("[PayStock] Local transaction failed after channel payment succeeded, initiating compensate refund: orderId={}", orderId, e);
            try {
                gateway.refund(orderId, order.getAmount());
                log.info("[PayStock] Compensate refund succeeded: orderId={}", orderId);
            } catch (Exception refundEx) {
                log.error("[PayStock] Compensate refund also failed, requires manual intervention: orderId={}", orderId, refundEx);
            }
            throw new RuntimeException("Payment failed (compensate refund attempted): " + orderId, e);
        }

        if (messageProducer.send(localMessage)) {
            msgRepo.updateStatus(localMessage.getId(), MsgStatus.SENT);
        }

        order.setStatus(OrderStatus.PAID);
        order.setChannelTradeNo(payResult.channelTradeNo());
        log.info("[PayStock] Payment completed: orderId={}, tradeNo={}", orderId, payResult.channelTradeNo());
        return order;
    }

    /**
     * 退款：调渠道退款 -> 本地事务(订单REFUNDED + 退款记录 + 消息PENDING) -> 发MQ。
     * 若渠道退款成功但本地事务失败，标记订单为 REFUNDING 等待人工介入。
     */
    public RefundRecord refund(String orderId) {
        PayOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.STOCK_DEDUCTED) {
            throw new IllegalStateException("Order cannot be refunded, current status: " + order.getStatus());
        }

        PayChannelGateway gateway = channelRouter.route(order.getChannel());
        PayChannelGateway.RefundResult refundResult = gateway.refund(order.getOrderId(), order.getAmount());
        if (!refundResult.success()) {
            throw new RuntimeException("Channel refund failed for order: " + orderId);
        }

        String refundId = UUID.randomUUID().toString();
        String msgId = UUID.randomUUID().toString();
        String payload = buildStockPayload(order);

        RefundRecord record = RefundRecord.builder()
                .refundId(refundId)
                .orderId(orderId)
                .amount(order.getAmount())
                .status(RefundStatus.SUCCESS)
                .channelRefundNo(refundResult.channelRefundNo())
                .build();

        LocalMessage localMessage;
        try {
            localMessage = txTemplate.execute(status -> {
                orderRepo.updateStatus(orderId, OrderStatus.REFUNDED, order.getStatus());
                refundRepo.insert(record);

                LocalMessage msg = LocalMessage.builder()
                        .id(msgId)
                        .orderId(orderId)
                        .msgType(MsgType.RESTORE_STOCK)
                        .payload(payload)
                        .status(MsgStatus.PENDING)
                        .nextRetryTime(LocalDateTime.now().plusSeconds(COMPENSATE_DELAY_SECONDS))
                        .build();
                msgRepo.insert(msg);
                return msg;
            });
        } catch (Exception e) {
            log.error("[PayStock] Local transaction failed after channel refund succeeded: orderId={}, channelRefundNo={}. " +
                    "Marking order as REFUNDING for manual reconciliation.", orderId, refundResult.channelRefundNo(), e);
            try {
                orderRepo.updateStatus(orderId, OrderStatus.REFUNDING, order.getStatus());
            } catch (Exception markEx) {
                log.error("[PayStock] Failed to mark order as REFUNDING, requires manual intervention: orderId={}", orderId, markEx);
            }
            throw new RuntimeException("Refund local transaction failed (channel refund succeeded, needs reconciliation): " + orderId, e);
        }

        if (messageProducer.send(localMessage)) {
            msgRepo.updateStatus(localMessage.getId(), MsgStatus.SENT);
        }

        log.info("[PayStock] Refund completed: orderId={}, refundId={}", orderId, refundId);
        return record;
    }

    private String buildStockPayload(PayOrder order) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("orderId", order.getOrderId(),
                            "skuId", order.getSkuId(),
                            "quantity", order.getQuantity()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build payload", e);
        }
    }
}
