package com.example.demo.payment.channel.mock;

import com.example.demo.payment.channel.PaymentChannelGateway;
import com.example.demo.payment.channel.PaymentChannelRequest;
import com.example.demo.payment.channel.PaymentChannelResult;
import com.example.demo.payment.channel.RefundChannelRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付宝渠道 Mock 网关。
 * 生产环境替换为对支付宝 SDK（alipay-sdk-java）的封装。
 */
@Slf4j
@Component
public class AlipayGateway implements PaymentChannelGateway {

    @Override
    public PaymentChannelResult pay(PaymentChannelRequest request) {
        log.info("[Alipay] 发起支付: outTradeNo={}, amount={}", request.getOutTradeNo(), request.getAmount());
        String channelTradeNo = "ALIPAY-" + System.currentTimeMillis() + "-" + request.getOutTradeNo().hashCode();
        log.info("[Alipay] 支付成功: channelTradeNo={}", channelTradeNo);
        return PaymentChannelResult.success(channelTradeNo);
    }

    @Override
    public PaymentChannelResult refund(RefundChannelRequest request) {
        log.info("[Alipay] 发起退款: outRefundNo={}, amount={}", request.getOutRefundNo(), request.getRefundAmount());
        String channelRefundNo = "ALIPAY-REFUND-" + System.currentTimeMillis();
        log.info("[Alipay] 退款成功: channelRefundNo={}", channelRefundNo);
        return PaymentChannelResult.successRefund(channelRefundNo);
    }
}
