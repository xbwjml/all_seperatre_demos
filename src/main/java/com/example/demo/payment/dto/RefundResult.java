package com.example.demo.payment.dto;

import com.example.demo.payment.domain.Refund;
import com.example.demo.payment.domain.RefundStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class RefundResult {

    private String outRefundNo;
    private String outTradeNo;
    private String channelRefundNo;
    private BigDecimal refundAmount;
    private String reason;
    private RefundStatus status;
    private String failReason;
    private LocalDateTime refundedAt;
    private LocalDateTime createdAt;

    public static RefundResult from(Refund refund) {
        RefundResult r = new RefundResult();
        r.setOutRefundNo(refund.getOutRefundNo());
        r.setOutTradeNo(refund.getOutTradeNo());
        r.setChannelRefundNo(refund.getChannelRefundNo());
        r.setRefundAmount(refund.getRefundAmount());
        r.setReason(refund.getReason());
        r.setStatus(refund.getStatus());
        r.setFailReason(refund.getFailReason());
        r.setRefundedAt(refund.getRefundedAt());
        r.setCreatedAt(refund.getCreatedAt());
        return r;
    }
}
