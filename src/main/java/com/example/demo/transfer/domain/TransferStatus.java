package com.example.demo.transfer.domain;

/**
 * 转账单状态机（单向流转，禁止逆向）
 *
 * PENDING → PROCESSING → SUCCESS
 *                      → FAILED
 *         → CANCELLED
 */
public enum TransferStatus {
    PENDING,        // 待处理
    PROCESSING,     // 处理中
    SUCCESS,        // 成功
    FAILED,         // 失败
    CANCELLED       // 已取消
}
