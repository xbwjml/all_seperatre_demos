package com.example.demo.payment.dto;

import com.example.demo.payment.domain.PaymentChannel;
import com.example.demo.payment.domain.PaymentOrder;
import com.example.demo.payment.domain.PaymentStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class PaymentResult {

    private String outTradeNo;
    private String channelTradeNo;
    private String userId;
    private BigDecimal amount;
    private BigDecimal refundedAmount;
    private PaymentChannel channel;
    private PaymentStatus status;
    private String subject;
    private String failReason;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;

    public static PaymentResult from(PaymentOrder order) {
        PaymentResult r = new PaymentResult();
        r.setOutTradeNo(order.getOutTradeNo());
        r.setChannelTradeNo(order.getChannelTradeNo());
        r.setUserId(order.getUserId());
        r.setAmount(order.getAmount());
        r.setRefundedAmount(order.getRefundedAmount());
        r.setChannel(order.getChannel());
        r.setStatus(order.getStatus());
        r.setSubject(order.getSubject());
        r.setFailReason(order.getFailReason());
        r.setPaidAt(order.getPaidAt());
        r.setCreatedAt(order.getCreatedAt());
        return r;
    }
}
