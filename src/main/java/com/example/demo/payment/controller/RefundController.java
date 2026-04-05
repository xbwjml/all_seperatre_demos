package com.example.demo.payment.controller;

import com.example.demo.payment.common.ApiResponse;
import com.example.demo.payment.dto.CreateRefundRequest;
import com.example.demo.payment.dto.RefundResult;
import com.example.demo.payment.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 退款接口。
 *
 * <pre>
 * POST  /api/refunds                     发起退款（幂等，支持部分退款）
 * GET   /api/refunds/{outRefundNo}       查询退款单
 * GET   /api/refunds/trade/{outTradeNo}  查询某支付订单的所有退款记录
 * </pre>
 */
@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    public ApiResponse<RefundResult> refund(@RequestBody CreateRefundRequest request) {
        return ApiResponse.success(refundService.refund(request));
    }

    @GetMapping("/{outRefundNo}")
    public ApiResponse<RefundResult> queryRefund(@PathVariable String outRefundNo) {
        return ApiResponse.success(refundService.queryRefund(outRefundNo));
    }

    @GetMapping("/trade/{outTradeNo}")
    public ApiResponse<List<RefundResult>> queryRefundsByTrade(@PathVariable String outTradeNo) {
        return ApiResponse.success(refundService.queryRefundsByTradeNo(outTradeNo));
    }
}
