package com.example.demo.payment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentChannel {
    ALIPAY("支付宝"),
    WECHAT_PAY("微信支付"),
    BALANCE("余额支付");

    private final String displayName;
}
