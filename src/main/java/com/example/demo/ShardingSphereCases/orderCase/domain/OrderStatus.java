package com.example.demo.ShardingSphereCases.orderCase.domain;

import java.util.Arrays;
import java.util.Set;

/**
 * 订单状态机：
 * <pre>
 *   PENDING_PAY ── pay ──> PAID ── ship ──> SHIPPED ── confirm ──> COMPLETED
 *        │                  │                  │                       │
 *      cancel             refund             refund                  refund
 *        ▼                  ▼                  ▼                       ▼
 *    CANCELLED          REFUNDING          REFUNDING               REFUNDING
 * </pre>
 * CANCELLED 与 REFUNDING 为终态。
 */
public enum OrderStatus {

    PENDING_PAY((byte) 0, "待支付"),
    PAID((byte) 1, "已支付"),
    SHIPPED((byte) 2, "已发货"),
    COMPLETED((byte) 3, "已完成"),
    CANCELLED((byte) 4, "已取消"),
    REFUNDING((byte) 5, "退款中");

    private final byte code;
    private final String label;

    OrderStatus(byte code, String label) {
        this.code = code;
        this.label = label;
    }

    public byte getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static OrderStatus of(byte code) {
        return Arrays.stream(values())
                .filter(s -> s.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown OrderStatus code: " + code));
    }

    /** 当前状态允许迁移到的下一状态集合（白名单）。 */
    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING_PAY -> Set.of(PAID, CANCELLED);
            case PAID        -> Set.of(SHIPPED, REFUNDING);
            case SHIPPED     -> Set.of(COMPLETED, REFUNDING);
            case COMPLETED   -> Set.of(REFUNDING);
            case CANCELLED, REFUNDING -> Set.of();
        };
    }

    public boolean canTransitTo(OrderStatus target) {
        return allowedNext().contains(target);
    }
}
