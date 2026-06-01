package com.example.demo.ShardingSphereCases.orderCase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class CreateOrderRequest {

    private Long userId;
    private Long sellerId;
    private BigDecimal payAmount;
    private Byte payType;
    private String remark;
    private List<Item> items;

    @Data
    public static class Item {
        private Long skuId;
        private Long spuId;
        private String skuName;
        private BigDecimal price;
        private Integer quantity;
    }
}
