package com.example.demo.ShardingSphereCases.orderCase.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单明细行。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    private Long orderId;
    private Long userId;
    private Long skuId;
    private Long spuId;
    private String skuName;
    private BigDecimal price;
    private int quantity;
    private LocalDateTime createTime;
}
