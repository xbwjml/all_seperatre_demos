package com.example.demo.payment.controller;

import com.example.demo.payment.common.ApiResponse;
import com.example.demo.payment.dto.CreatePaymentRequest;
import com.example.demo.payment.dto.PaymentResult;
import com.example.demo.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 支付接口。
 *
 * <pre>
 * POST  /api/payments                    发起支付（幂等，outTradeNo 相同时返回已有结果）
 * GET   /api/payments/{outTradeNo}       查询支付状态
 * POST  /api/payments/{outTradeNo}/close 关闭订单（仅 PENDING/PAYING 状态可关闭）
 * </pre>
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ApiResponse<PaymentResult> pay(@RequestBody CreatePaymentRequest request) {
        return ApiResponse.success(paymentService.pay(request));
    }

    @GetMapping("/{outTradeNo}")
    public ApiResponse<PaymentResult> queryPayment(@PathVariable String outTradeNo) {
        return ApiResponse.success(paymentService.queryPayment(outTradeNo));
    }

    @PostMapping("/{outTradeNo}/close")
    public ApiResponse<PaymentResult> closePayment(@PathVariable String outTradeNo) {
        return ApiResponse.success(paymentService.closePayment(outTradeNo));
    }
}
