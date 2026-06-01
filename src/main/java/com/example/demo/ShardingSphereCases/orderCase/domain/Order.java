package com.example.demo.ShardingSphereCases.orderCase.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单聚合根（持久化对象）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private Long orderId;
    private String orderNo;
    private Long userId;
    private Long sellerId;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private byte status;
    private Byte payType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime payTime;
    private String remark;
    private int version;

    /** 仅业务装载，不参与 t_order 主表持久化 */
    private List<OrderItem> items;

    public OrderStatus statusEnum() {
        return OrderStatus.of(status);
    }
}
