package com.example.demo.localMsgTable.payStock.controller;

import com.example.demo.localMsgTable.payStock.domain.PayOrder;
import com.example.demo.localMsgTable.payStock.domain.RefundRecord;
import com.example.demo.localMsgTable.payStock.enums.PayChannel;
import com.example.demo.localMsgTable.payStock.service.PayStockPaymentAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/pay-stock")
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PayOrderController {

    private final PayStockPaymentAppService paymentService;

    @PostMapping("/orders")
    public ResponseEntity<PayOrder> createOrder(@RequestBody CreateOrderRequest req) {
        PayOrder order = paymentService.createOrder(
                req.skuId(), req.quantity(), req.amount(), req.channel());
        return ResponseEntity.ok(order);
    }

    @PostMapping("/orders/{orderId}/pay")
    public ResponseEntity<PayOrder> pay(@PathVariable String orderId) {
        PayOrder order = paymentService.pay(orderId);
        return ResponseEntity.ok(order);
    }

    @PostMapping("/orders/{orderId}/refund")
    public ResponseEntity<RefundRecord> refund(@PathVariable String orderId) {
        RefundRecord record = paymentService.refund(orderId);
        return ResponseEntity.ok(record);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    public record CreateOrderRequest(String skuId, int quantity, BigDecimal amount, PayChannel channel) {}
}
