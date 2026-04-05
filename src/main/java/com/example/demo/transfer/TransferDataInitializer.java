package com.example.demo.transfer;

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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * 启动时通过 HTTP 调用 AccountController 的所有接口进行集成测试。
 * 覆盖正常场景（✅）和异常场景（❌）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.transfer.test-initializer.enabled", havingValue = "true")
public class TransferDataInitializer implements CommandLineRunner {

    private final ObjectMapper objectMapper;

    @Value("${server.port:8080}")
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public void run(String... args) throws Exception {
        String base = "http://localhost:" + port;

        separator("AccountController API 测试开始");

        String aliceId = case1_createAlice(base);
        String bobId   = case2_createBob(base);
        case3_getAccount(base, aliceId);
        case4_deposit(base, aliceId);
        case5_getAccountAfterDeposit(base, aliceId);
        case6_getLedger(base, aliceId);
        case7_getAccountNotFound(base);
        case8_depositNegativeAmount(base, aliceId);
        case9_createAccountMissingUserId(base);
        case10_transferInsufficientBalance(base, aliceId, bobId);

        separator("测试完成  Alice=" + aliceId + "  Bob=" + bobId);
    }

    // ── 测试用例 ─────────────────────────────────────────────────────────────

    /** 用例 1：POST /api/accounts  创建账户 Alice，返回 accountId */
    private String case1_createAlice(String base) throws Exception {
        logCase(1, "POST /api/accounts  创建账户 Alice");
        String resp = post(base + "/api/accounts",
                """
                {"userId":"user-alice","name":"Alice","initialBalance":10000.00}
                """);
        logResponse(resp);
        return extract(resp, "data", "id");
    }

    /** 用例 2：POST /api/accounts  创建账户 Bob，返回 accountId */
    private String case2_createBob(String base) throws Exception {
        logCase(2, "POST /api/accounts  创建账户 Bob");
        String resp = post(base + "/api/accounts",
                """
                {"userId":"user-bob","name":"Bob","initialBalance":5000.00}
                """);
        logResponse(resp);
        return extract(resp, "data", "id");
    }

    /** 用例 3：GET /api/accounts/{accountId}  查询账户（正常） */
    private void case3_getAccount(String base, String accountId) {
        logCase(3, "GET /api/accounts/{accountId}  查询 Alice 账户信息");
        logResponse(get(base + "/api/accounts/" + accountId));
    }

    /** 用例 4：POST /api/accounts/{accountId}/deposit  充值（正常） */
    private void case4_deposit(String base, String accountId) {
        logCase(4, "POST /api/accounts/{accountId}/deposit  Alice 充值 500 元");
        logResponse(post(base + "/api/accounts/" + accountId + "/deposit",
                """
                {"amount":500.00}
                """));
    }

    /** 用例 5：GET /api/accounts/{accountId}  验证充值后余额已更新 */
    private void case5_getAccountAfterDeposit(String base, String accountId) {
        logCase(5, "GET /api/accounts/{accountId}  验证 Alice 充值后余额=10500");
        logResponse(get(base + "/api/accounts/" + accountId));
    }

    /** 用例 6：GET /api/accounts/{accountId}/ledger  查询账务流水（初始无转账，应为空[]） */
    private void case6_getLedger(String base, String accountId) {
        logCase(6, "GET /api/accounts/{accountId}/ledger  查询 Alice 账务流水（应为空[]）");
        logResponse(get(base + "/api/accounts/" + accountId + "/ledger"));
    }

    /** 用例 7（异常）：GET /api/accounts/nonexistent  查询不存在的账户 → 期望 404 */
    private void case7_getAccountNotFound(String base) {
        logCase(7, "GET /api/accounts/nonexistent  查询不存在账户 → 期望 404");
        logResponse(getWithError(base + "/api/accounts/nonexistent"));
    }

    /** 用例 8（异常）：POST /api/accounts/{accountId}/deposit  充值负数金额 → 期望 400 */
    private void case8_depositNegativeAmount(String base, String accountId) {
        logCase(8, "POST /api/accounts/{accountId}/deposit  充值 -100 → 期望 400");
        logResponse(postWithError(base + "/api/accounts/" + accountId + "/deposit",
                """
                {"amount":-100}
                """));
    }

    /** 用例 9（异常）：POST /api/accounts  userId 为空 → 期望 400 */
    private void case9_createAccountMissingUserId(String base) {
        logCase(9, "POST /api/accounts  userId 为空 → 期望 400");
        logResponse(postWithError(base + "/api/accounts",
                """
                {"userId":"","name":"Ghost","initialBalance":0}
                """));
    }

    /**
     * 用例 10（异常）：POST /api/transfers  转账金额超出余额 → 期望 400 余额不足。
     * Alice 当前余额 10500，尝试转出 99999，应被拒绝。
     */
    private void case10_transferInsufficientBalance(String base, String fromId, String toId) {
        logCase(10, "POST /api/transfers  转账金额(99999)超出余额(10500) → 期望 400 余额不足");
        logResponse(postWithError(base + "/api/transfers",
                String.format("""
                {"transferNo":"TXN-INSUFFICIENT-001","fromAccountId":"%s","toAccountId":"%s","amount":99999.00,"remark":"余额不足测试"}
                """, fromId, toId)));
    }

    // ── HTTP 工具方法 ────────────────────────────────────────────────────────

    private String post(String url, String json) {
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.POST, jsonEntity(json), String.class);
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

    /** 从 ApiResponse JSON 中提取嵌套字段值，如 extract(json, "data", "id") */
    private String extract(String json, String... path) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        for (String key : path) {
            node = node.path(key);
        }
        return node.asText();
    }

    // ── 日志格式化 ───────────────────────────────────────────────────────────

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
