package com.example.demo.payment.channel;

import lombok.Data;

/**
 * 渠道调用统一结果。适用于支付和退款两类调用。
 * channelTradeNo  —— 支付成功时渠道返回的流水号
 * channelRefundNo —— 退款成功时渠道返回的退款流水号
 */
@Data
public class PaymentChannelResult {

    private boolean success;
    private String channelTradeNo;
    private String channelRefundNo;
    private String failReason;

    public static PaymentChannelResult success(String channelTradeNo) {
        PaymentChannelResult r = new PaymentChannelResult();
        r.setSuccess(true);
        r.setChannelTradeNo(channelTradeNo);
        return r;
    }

    public static PaymentChannelResult successRefund(String channelRefundNo) {
        PaymentChannelResult r = new PaymentChannelResult();
        r.setSuccess(true);
        r.setChannelRefundNo(channelRefundNo);
        return r;
    }

    public static PaymentChannelResult fail(String reason) {
        PaymentChannelResult r = new PaymentChannelResult();
        r.setSuccess(false);
        r.setFailReason(reason);
        return r;
    }
}
