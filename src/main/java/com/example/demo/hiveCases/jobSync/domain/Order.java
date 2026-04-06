package com.example.demo.hiveCases.jobSync.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户订单实体，对应 MySQL orders 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    /** 订单号（主键） */
    private String orderId;

    /** 用户 ID */
    private Long userId;

    /** 商品名称 */
    private String productName;

    /** 订单金额 */
    private BigDecimal amount;

    /**
     * 订单状态：PENDING / PAID / CANCELLED / REFUNDED
     */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 最后更新时间（增量同步的基准字段） */
    private LocalDateTime updateTime;
}
