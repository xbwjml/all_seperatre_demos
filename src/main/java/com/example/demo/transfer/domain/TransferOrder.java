package com.example.demo.transfer.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账单。transferNo 为客户端生成的幂等键，数据库层面设唯一索引。
 */
@Data
public class TransferOrder {

    private String id;
    /** 幂等键，由调用方生成（如 UUID），防止重复转账 */
    private String transferNo;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private TransferStatus status;
    private String remark;
    /** 失败原因，仅 FAILED 时有值 */
    private String failReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
