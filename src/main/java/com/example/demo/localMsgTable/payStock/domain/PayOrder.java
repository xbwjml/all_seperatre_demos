package com.example.demo.localMsgTable.payStock.domain;

import com.example.demo.localMsgTable.payStock.enums.OrderStatus;
import com.example.demo.localMsgTable.payStock.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOrder {

    private String orderId;
    private String skuId;
    private int quantity;
    private BigDecimal amount;
    private PayChannel channel;
    private OrderStatus status;
    private String channelTradeNo;
}
