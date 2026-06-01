package com.example.demo.ShardingSphereCases.orderCase.dto;

import lombok.Data;

@Data
public class UpdateOrderStatusRequest {

    /** 业主 user_id（用于触发分片路由） */
    private Long userId;

    /** 目标状态 code, 见 OrderStatus */
    private Byte targetStatus;

    /** 当前期望版本号 (乐观锁) */
    private Integer expectedVersion;
}
