package com.example.demo.transfer.dto;

import com.example.demo.transfer.domain.TransferOrder;
import com.example.demo.transfer.domain.TransferStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TransferResult {

    private String transferNo;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private TransferStatus status;
    private String remark;
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TransferResult from(TransferOrder order) {
        TransferResult r = new TransferResult();
        r.setTransferNo(order.getTransferNo());
        r.setFromAccountId(order.getFromAccountId());
        r.setToAccountId(order.getToAccountId());
        r.setAmount(order.getAmount());
        r.setStatus(order.getStatus());
        r.setRemark(order.getRemark());
        r.setFailReason(order.getFailReason());
        r.setCreatedAt(order.getCreatedAt());
        r.setUpdatedAt(order.getUpdatedAt());
        return r;
    }
}
