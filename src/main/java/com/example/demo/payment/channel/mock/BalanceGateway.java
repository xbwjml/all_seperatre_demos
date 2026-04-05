package com.example.demo.payment.channel.mock;

import com.example.demo.payment.channel.PaymentChannelGateway;
import com.example.demo.payment.channel.PaymentChannelRequest;
import com.example.demo.payment.channel.PaymentChannelResult;
import com.example.demo.payment.channel.RefundChannelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 余额支付渠道 Mock 网关。
 * 生产环境需真正检查用户账户余额，此处仅模拟成功。
 */
@Slf4j
@Component
public class BalanceGateway implements PaymentChannelGateway {

    @Override
    public PaymentChannelResult pay(PaymentChannelRequest request) {
        log.info("[Balance] 余额支付: outTradeNo={}, userId={}, amount={}",
                request.getOutTradeNo(), request.getUserId(), request.getAmount());
        String channelTradeNo = "BAL-" + System.currentTimeMillis();
        log.info("[Balance] 支付成功: channelTradeNo={}", channelTradeNo);
        return PaymentChannelResult.success(channelTradeNo);
    }

    @Override
    public PaymentChannelResult refund(RefundChannelRequest request) {
        log.info("[Balance] 余额退款: outRefundNo={}, amount={}", request.getOutRefundNo(), request.getRefundAmount());
        String channelRefundNo = "BAL-REFUND-" + System.currentTimeMillis();
        log.info("[Balance] 退款成功: channelRefundNo={}", channelRefundNo);
        return PaymentChannelResult.successRefund(channelRefundNo);
    }
}
