package com.example.demo.payment.channel;

/**
 * 支付渠道网关统一接口。
 * 每个渠道（支付宝、微信、余额）实现此接口，屏蔽渠道差异。
 * 生产环境对应各渠道 SDK 的封装层。
 */
public interface PaymentChannelGateway {

    PaymentChannelResult pay(PaymentChannelRequest request);

    PaymentChannelResult refund(RefundChannelRequest request);
}
