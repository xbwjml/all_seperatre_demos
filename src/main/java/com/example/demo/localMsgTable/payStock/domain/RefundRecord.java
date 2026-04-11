package com.example.demo.localMsgTable.payStock.domain;

import com.example.demo.localMsgTable.payStock.enums.RefundStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRecord {

    private String refundId;
    private String orderId;
    private BigDecimal amount;
    private RefundStatus status;
    private String channelRefundNo;
}
