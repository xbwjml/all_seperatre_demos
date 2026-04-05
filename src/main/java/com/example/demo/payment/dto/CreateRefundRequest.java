package com.example.demo.payment.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateRefundRequest {
    /** 商户退款单号（幂等键，客户端生成） */
    private String outRefundNo;
    /** 原支付订单号 */
    private String outTradeNo;
    private BigDecimal refundAmount;
    private String reason;
}
