package com.example.demo.payment.channel;

import lombok.Data;

import java.math.BigDecimal;

/** 调用渠道时的统一支付请求 */
@Data
public class PaymentChannelRequest {

    private String outTradeNo;
    private String userId;
    private BigDecimal amount;
    private String subject;
}
