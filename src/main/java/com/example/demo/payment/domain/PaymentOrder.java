package com.example.demo.payment.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付订单。
 * outTradeNo 为商户侧唯一订单号（幂等键），channelTradeNo 为渠道侧流水号。
 * refundedAmount 追踪已退款金额，用于部分退款校验和全额退款状态流转。
 */
@Data
public class PaymentOrder {

    private String id;
    /** 商户订单号（幂等键，客户端生成） */
    private String outTradeNo;
    /** 支付渠道流水号（支付成功后由渠道返回） */
    private String channelTradeNo;
    private String userId;
    private BigDecimal amount;
    /** 已退款金额，初始为 0 */
    private BigDecimal refundedAmount;
    private PaymentChannel channel;
    private PaymentStatus status;
    /** 商品描述 */
    private String subject;
    private String failReason;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 可退款金额 = 实付金额 - 已退款金额 */
    public BigDecimal getRefundableAmount() {
        return amount.subtract(refundedAmount);
    }
}
