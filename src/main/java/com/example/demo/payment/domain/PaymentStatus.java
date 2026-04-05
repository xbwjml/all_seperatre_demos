package com.example.demo.payment.domain;

/**
 * 支付单状态机（单向流转）
 *
 * PENDING → PAYING → SUCCESS → REFUNDED（全额退款后）
 *                  → FAILED
 *         → CLOSED（主动关单）
 */
public enum PaymentStatus {
    PENDING,    // 待支付
    PAYING,     // 支付中
    SUCCESS,    // 支付成功
    FAILED,     // 支付失败
    CLOSED,     // 已关闭（超时或主动关单）
    REFUNDED    // 已全额退款
}
