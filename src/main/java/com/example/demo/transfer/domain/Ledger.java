package com.example.demo.transfer.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账务流水（复式记账）。每笔转账产生两条流水：一借一贷。
 * 流水记录只增不改，amount > 0 为收入，< 0 为支出。
 * balanceAfter 为余额快照，可独立还原任意时刻的账户余额。
 */
@Data
public class Ledger {

    private String id;
    private String accountId;
    private String transferNo;
    /** 正数=收入，负数=支出 */
    private BigDecimal amount;
    /** 本条流水产生后的账户余额快照 */
    private BigDecimal balanceAfter;
    private String description;
    private LocalDateTime createdAt;
}
