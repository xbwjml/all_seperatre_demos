package com.example.demo.localMsgTable.payStock.channel;

import com.example.demo.localMsgTable.payStock.enums.PayChannel;

import java.math.BigDecimal;

public interface PayChannelGateway {

    PayChannel channel();

    PayResult pay(String orderId, BigDecimal amount);

    RefundResult refund(String orderId, BigDecimal amount);

    record PayResult(boolean success, String channelTradeNo) {}

    record RefundResult(boolean success, String channelRefundNo) {}
}
