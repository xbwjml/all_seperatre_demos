package com.example.demo.transfer.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户领域对象。
 * balance 字段通过 deduct/credit 方法修改，确保业务约束在领域层内聚。
 * 并发锁不内嵌于此对象，由 AccountRepository 统一管理（关注点分离）。
 */
@Data
public class Account {

    private String id;
    private String userId;
    private String name;
    /** 账户总余额（= 可用 + 冻结） */
    private BigDecimal balance;
    /** 冻结金额（预留字段，用于担保交易等场景） */
    private BigDecimal frozen;
    /** 乐观锁版本号，每次变更 +1 */
    private int version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 可用余额 = 总余额 - 冻结金额 */
    public BigDecimal getAvailableBalance() {
        return balance.subtract(frozen);
    }

    /**
     * 扣款。必须在持有账户锁的情况下调用。
     */
    public void deduct(BigDecimal amount) {
        if (getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalStateException("余额不足");
        }
        this.balance = this.balance.subtract(amount);
        this.version++;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 收款。必须在持有账户锁的情况下调用。
     */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.version++;
        this.updatedAt = LocalDateTime.now();
    }
}
