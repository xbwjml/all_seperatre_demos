package com.example.demo.payment.channel;

import lombok.Data;

import java.math.BigDecimal;

/** 调用渠道时的统一退款请求 */
@Data
public class RefundChannelRequest {

    private String outRefundNo;
    /** 原支付订单商户号 */
    private String outTradeNo;
    /** 渠道支付流水号 */
    private String channelTradeNo;
    private BigDecimal refundAmount;
    private String reason;
}
