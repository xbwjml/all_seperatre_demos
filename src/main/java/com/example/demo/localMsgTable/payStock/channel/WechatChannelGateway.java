package com.example.demo.localMsgTable.payStock.channel;

import com.example.demo.localMsgTable.payStock.enums.PayChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class WechatChannelGateway implements PayChannelGateway {

    @Override
    public PayChannel channel() {
        return PayChannel.WECHAT;
    }

    @Override
    public PayResult pay(String orderId, BigDecimal amount) {
        log.info("[WechatPay] Processing payment for order={}, amount={}", orderId, amount);
        String tradeNo = "WX_" + UUID.randomUUID().toString().substring(0, 16);
        return new PayResult(true, tradeNo);
    }

    @Override
    public RefundResult refund(String orderId, BigDecimal amount) {
        log.info("[WechatPay] Processing refund for order={}, amount={}", orderId, amount);
        String refundNo = "WX_RF_" + UUID.randomUUID().toString().substring(0, 12);
        return new RefundResult(true, refundNo);
    }
}
