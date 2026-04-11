package com.example.demo.localMsgTable.payStock.domain;

import com.example.demo.localMsgTable.payStock.enums.MsgStatus;
import com.example.demo.localMsgTable.payStock.enums.MsgType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalMessage {

    private String id;
    private String orderId;
    private MsgType msgType;
    private String payload;
    private MsgStatus status;
    @Builder.Default
    private int retryCount = 0;
    @Builder.Default
    private int maxRetry = 5;
    private LocalDateTime nextRetryTime;
}
