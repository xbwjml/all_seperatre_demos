package com.example.demo.payment.domain;

public enum RefundStatus {
    PENDING,     // 待处理
    PROCESSING,  // 处理中
    SUCCESS,     // 退款成功
    FAILED       // 退款失败
}
