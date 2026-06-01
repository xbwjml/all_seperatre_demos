package com.example.demo.ShardingSphereCases.orderCase.exception;

/** 订单业务异常（受检），由 Controller 层统一翻译为 ApiResp。 */
public class OrderException extends RuntimeException {

    private final int code;

    public OrderException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static OrderException notFound(long orderId) {
        return new OrderException(404, "Order not found: " + orderId);
    }

    public static OrderException badStatusTransition(String detail) {
        return new OrderException(409, "Illegal status transition: " + detail);
    }

    public static OrderException versionConflict(long orderId) {
        return new OrderException(409, "Version conflict on order: " + orderId);
    }

    public static OrderException badRequest(String msg) {
        return new OrderException(400, msg);
    }
}
