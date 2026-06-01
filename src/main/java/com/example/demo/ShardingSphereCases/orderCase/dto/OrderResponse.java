package com.example.demo.ShardingSphereCases.orderCase.dto;

import com.example.demo.ShardingSphereCases.orderCase.domain.Order;
import com.example.demo.ShardingSphereCases.orderCase.domain.OrderItem;
import com.example.demo.ShardingSphereCases.orderCase.domain.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderResponse {

    private Long orderId;
    private String orderNo;
    private Long userId;
    private Long sellerId;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private byte statusCode;
    private String statusLabel;
    private Byte payType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime payTime;
    private String remark;
    private int version;
    private List<OrderItem> items;

    /** 演示用: 显示该订单实际落在哪个分片 */
    private Integer shardDbIndex;
    private Integer shardTableIndex;

    public static OrderResponse from(Order order, Integer dbIdx, Integer tblIdx) {
        OrderResponse r = new OrderResponse();
        r.setOrderId(order.getOrderId());
        r.setOrderNo(order.getOrderNo());
        r.setUserId(order.getUserId());
        r.setSellerId(order.getSellerId());
        r.setTotalAmount(order.getTotalAmount());
        r.setPayAmount(order.getPayAmount());
        r.setStatusCode(order.getStatus());
        r.setStatusLabel(OrderStatus.of(order.getStatus()).getLabel());
        r.setPayType(order.getPayType());
        r.setCreateTime(order.getCreateTime());
        r.setUpdateTime(order.getUpdateTime());
        r.setPayTime(order.getPayTime());
        r.setRemark(order.getRemark());
        r.setVersion(order.getVersion());
        r.setItems(order.getItems());
        r.setShardDbIndex(dbIdx);
        r.setShardTableIndex(tblIdx);
        return r;
    }
}
