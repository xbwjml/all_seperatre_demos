package com.example.demo.payment.exception;

public class PaymentOrderNotFoundException extends PaymentException {

    public PaymentOrderNotFoundException(String outTradeNo) {
        super("支付订单不存在: " + outTradeNo);
    }
}
