package com.example.demo.payment.dto;

import com.example.demo.payment.domain.PaymentChannel;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePaymentRequest {
    /** 商户订单号（幂等键，客户端生成，建议 UUID） */
    private String outTradeNo;
    private String userId;
    private BigDecimal amount;
    private PaymentChannel channel;
    /** 商品描述 */
    private String subject;
}
