package com.example.demo.payment.common;

import com.example.demo.payment.exception.PaymentException;
import com.example.demo.payment.exception.PaymentOrderNotFoundException;
import com.example.demo.payment.exception.RefundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice(basePackages = "com.example.demo.payment")
public class PaymentGlobalExceptionHandler {

    @ExceptionHandler(PaymentOrderNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(PaymentOrderNotFoundException e) {
        log.warn("订单不存在: {}", e.getMessage());
        return ApiResponse.error(404, e.getMessage());
    }

    @ExceptionHandler(RefundException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleRefund(RefundException e) {
        log.warn("退款异常: {}", e.getMessage());
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(PaymentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handlePayment(PaymentException e) {
        log.error("支付异常: {}", e.getMessage(), e);
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统内部错误: {}", e.getMessage(), e);
        return ApiResponse.error(500, "系统内部错误");
    }
}
