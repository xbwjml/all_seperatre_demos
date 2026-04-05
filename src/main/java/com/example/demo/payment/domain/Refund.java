package com.example.demo.payment.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单。outRefundNo 为商户侧唯一退款单号（幂等键）。
 * 每笔支付订单可发起多次部分退款，累计退款金额不超过支付金额。
 */
@Data
public class Refund {

    private String id;
    /** 商户退款单号（幂等键） */
    private String outRefundNo;
    /** 关联的原支付订单号 */
    private String outTradeNo;
    /** 原支付渠道流水号（退款时传给渠道） */
    private String channelTradeNo;
    /** 渠道退款流水号（退款成功后由渠道返回） */
    private String channelRefundNo;
    private BigDecimal refundAmount;
    private String reason;
    private RefundStatus status;
    private String failReason;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
