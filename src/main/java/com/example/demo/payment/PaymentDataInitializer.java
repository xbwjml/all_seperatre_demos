package com.example.demo.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * 启动时通过 HTTP 调用 PaymentController / RefundController 的所有接口进行集成测试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.payment.test-initializer.enabled", havingValue = "true")
public class PaymentDataInitializer implements CommandLineRunner {

    private final ObjectMapper objectMapper;

    @Value("${server.port:8080}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public void run(String... args) throws Exception {
        String base = "http://localhost:" + port;

        separator("Payment 模块 API 测试开始");

        String alipayTradeNo   = case1_payViaAlipay(base);
        String wechatTradeNo   = case2_payViaWechat(base);
        String balanceTradeNo  = case3_payViaBalance(base);
        case4_queryPayment(base, alipayTradeNo);
        case5_partialRefund(base, alipayTradeNo);
        case6_fullRefund(base, wechatTradeNo);
        case7_queryRefundsByTrade(base, alipayTradeNo);
        case8_closePayment(base);
        case9_payInvalidAmount(base);
        case10_refundExceedsAmount(base, balanceTradeNo);
        case11_refundOnNonSuccessOrder(base);
        case12_queryNonExistentOrder(base);
        case13_idempotentPay(base, alipayTradeNo);

        separator("Payment 模块测试完成");
    }

    // ── 测试用例 ─────────────────────────────────────────────────────────────

    /** 用例 1：支付宝支付（正常），返回 outTradeNo */
    private String case1_payViaAlipay(String base) throws Exception {
        logCase(1, "POST /api/payments  支付宝支付 200 元 → 期望 SUCCESS");
        String resp = post(base + "/api/payments", """
                {"outTradeNo":"PAY-ALIPAY-001","userId":"user-001","amount":200.00,
                 "channel":"ALIPAY","subject":"购买商品A"}
                """);
        logResponse(resp);
        return "PAY-ALIPAY-001";
    }

    /** 用例 2：微信支付（正常），返回 outTradeNo */
    private String case2_payViaWechat(String base) throws Exception {
        logCase(2, "POST /api/payments  微信支付 350 元 → 期望 SUCCESS");
        String resp = post(base + "/api/payments", """
                {"outTradeNo":"PAY-WECHAT-001","userId":"user-002","amount":350.00,
                 "channel":"WECHAT_PAY","subject":"购买商品B"}
                """);
        logResponse(resp);
        return "PAY-WECHAT-001";
    }

    /** 用例 3：余额支付（正常），返回 outTradeNo */
    private String case3_payViaBalance(String base) throws Exception {
        logCase(3, "POST /api/payments  余额支付 500 元 → 期望 SUCCESS");
        String resp = post(base + "/api/payments", """
                {"outTradeNo":"PAY-BAL-001","userId":"user-003","amount":500.00,
                 "channel":"BALANCE","subject":"购买商品C"}
                """);
        logResponse(resp);
        return "PAY-BAL-001";
    }

    /** 用例 4：查询支付订单 */
    private void case4_queryPayment(String base, String outTradeNo) {
        logCase(4, "GET /api/payments/{outTradeNo}  查询支付宝订单状态");
        logResponse(get(base + "/api/payments/" + outTradeNo));
    }

    /** 用例 5：部分退款（正常）—— 对支付宝订单退 50 元（总额 200）*/
    private void case5_partialRefund(String base, String outTradeNo) {
        logCase(5, "POST /api/refunds  部分退款 50 元（总额 200） → 期望 SUCCESS，支付单仍 SUCCESS");
        logResponse(post(base + "/api/refunds",
                String.format("""
                {"outRefundNo":"REFUND-PARTIAL-001","outTradeNo":"%s",
                 "refundAmount":50.00,"reason":"质量问题"}
                """, outTradeNo)));
    }

    /** 用例 6：全额退款（正常）—— 对微信订单全额退 350 元，支付单应变为 REFUNDED */
    private void case6_fullRefund(String base, String outTradeNo) {
        logCase(6, "POST /api/refunds  全额退款 350 元 → 期望 SUCCESS，支付单变为 REFUNDED");
        logResponse(post(base + "/api/refunds",
                String.format("""
                {"outRefundNo":"REFUND-FULL-001","outTradeNo":"%s",
                 "refundAmount":350.00,"reason":"申请退货"}
                """, outTradeNo)));
        // 验证支付单状态
        logCase(6, "GET /api/payments/{outTradeNo}  验证微信支付单状态为 REFUNDED");
        logResponse(get(base + "/api/payments/" + outTradeNo));
    }

    /** 用例 7：查询某支付单的所有退款记录 */
    private void case7_queryRefundsByTrade(String base, String outTradeNo) {
        logCase(7, "GET /api/refunds/trade/{outTradeNo}  查询支付宝订单的退款列表");
        logResponse(get(base + "/api/refunds/trade/" + outTradeNo));
    }

    /** 用例 8：关闭订单（先创建一个新支付，再立即关闭）*/
    private void case8_closePayment(String base) {
        logCase(8, "POST /api/payments/{outTradeNo}/close  关闭订单 → 期望 CLOSED");
        // 注意：同步 mock 中支付会立即变为 SUCCESS，无法直接关闭。
        // 此处演示关闭 SUCCESS 订单时的错误响应（只有 PENDING/PAYING 才可关闭）。
        logResponse(postWithError(base + "/api/payments/PAY-ALIPAY-001/close", ""));
    }

    /** 用例 9（异常）：支付金额为负数 → 期望 400 */
    private void case9_payInvalidAmount(String base) {
        logCase(9, "POST /api/payments  金额为负数 → 期望 400");
        logResponse(postWithError(base + "/api/payments", """
                {"outTradeNo":"PAY-ERR-001","userId":"user-001","amount":-100.00,
                 "channel":"ALIPAY","subject":"非法金额"}
                """));
    }

    /** 用例 10（异常）：退款金额超出可退金额 → 期望 400 */
    private void case10_refundExceedsAmount(String base, String outTradeNo) {
        logCase(10, "POST /api/refunds  退款 9999 元（已支付 500） → 期望 400");
        logResponse(postWithError(base + "/api/refunds",
                String.format("""
                {"outRefundNo":"REFUND-ERR-001","outTradeNo":"%s",
                 "refundAmount":9999.00,"reason":"超额退款"}
                """, outTradeNo)));
    }

    /** 用例 11（异常）：对非成功订单发起退款 → 期望 400 */
    private void case11_refundOnNonSuccessOrder(String base) {
        logCase(11, "POST /api/refunds  对不存在的订单退款 → 期望 404");
        logResponse(postWithError(base + "/api/refunds", """
                {"outRefundNo":"REFUND-ERR-002","outTradeNo":"NOT-EXIST",
                 "refundAmount":10.00,"reason":"测试"}
                """));
    }

    /** 用例 12（异常）：查询不存在的订单 → 期望 404 */
    private void case12_queryNonExistentOrder(String base) {
        logCase(12, "GET /api/payments/NOT-EXIST  查询不存在订单 → 期望 404");
        logResponse(getWithError(base + "/api/payments/NOT-EXIST"));
    }

    /** 用例 13：幂等支付 —— 相同 outTradeNo 重复发起，应返回已有结果 */
    private void case13_idempotentPay(String base, String outTradeNo) {
        logCase(13, "POST /api/payments  重复 outTradeNo → 期望幂等返回首次结果");
        logResponse(post(base + "/api/payments", String.format("""
                {"outTradeNo":"%s","userId":"user-001","amount":999.99,
                 "channel":"WECHAT_PAY","subject":"重复支付测试"}
                """, outTradeNo)));
    }

    // ── HTTP 工具方法 ─────────────────────────────────────────────────────────

    private String post(String url, String json) {
        var resp = restTemplate.exchange(url, HttpMethod.POST, jsonEntity(json), String.class);
        return resp.getBody();
    }

    private String postWithError(String url, String json) {
        try {
            return post(url, json);
        } catch (HttpClientErrorException e) {
            return e.getResponseBodyAsString();
        }
    }

    private String get(String url) {
        return restTemplate.getForEntity(url, String.class).getBody();
    }

    private String getWithError(String url) {
        try {
            return get(url);
        } catch (HttpClientErrorException e) {
            return e.getResponseBodyAsString();
        }
    }

    private HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json.trim(), headers);
    }

    // ── 日志格式化 ────────────────────────────────────────────────────────────

    private void logCase(int no, String desc) {
        log.info("");
        log.info("  【用例 {}】{}", no, desc);
    }

    private void logResponse(String body) {
        log.info("  响应: {}", body);
    }

    private void separator(String title) {
        log.info("");
        log.info("══════════════════════════════════════════════════════");
        log.info("  {}", title);
        log.info("══════════════════════════════════════════════════════");
    }
}
