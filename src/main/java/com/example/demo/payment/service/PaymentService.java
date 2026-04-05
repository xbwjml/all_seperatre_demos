package com.example.demo.payment.service;

import com.example.demo.payment.channel.ChannelRouter;
import com.example.demo.payment.channel.PaymentChannelRequest;
import com.example.demo.payment.channel.PaymentChannelResult;
import com.example.demo.payment.domain.PaymentChannel;
import com.example.demo.payment.domain.PaymentOrder;
import com.example.demo.payment.domain.PaymentStatus;
import com.example.demo.payment.dto.CreatePaymentRequest;
import com.example.demo.payment.dto.PaymentResult;
import com.example.demo.payment.exception.PaymentException;
import com.example.demo.payment.exception.PaymentOrderNotFoundException;
import com.example.demo.payment.repository.PaymentOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支付核心服务。
 *
 * <p>关键设计：
 * <ol>
 *   <li><b>幂等性</b>：outTradeNo 为唯一键，重复请求直接返回已有结果</li>
 *   <li><b>渠道解耦</b>：通过 ChannelRouter 分发，新增渠道无需改动此服务</li>
 *   <li><b>状态机</b>：PENDING → PAYING → SUCCESS/FAILED，单向流转</li>
 *   <li><b>异常安全</b>：无论渠道调用结果如何，最终都持久化订单状态</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final ChannelRouter channelRouter;

    // ── 发起支付 ──────────────────────────────────────────────────────────────

    public PaymentResult pay(CreatePaymentRequest request) {
        validateRequest(request);

        // 幂等：已存在则直接返回
        var existing = paymentOrderRepository.findByOutTradeNo(request.getOutTradeNo());
        if (existing.isPresent()) {
            log.info("重复支付请求，outTradeNo={}", request.getOutTradeNo());
            return PaymentResult.from(existing.get());
        }

        PaymentOrder order = buildOrder(request);
        paymentOrderRepository.save(order);

        // PENDING → PAYING
        order.setStatus(PaymentStatus.PAYING);
        order.setUpdatedAt(LocalDateTime.now());

        try {
            PaymentChannelRequest channelReq = buildChannelRequest(order);
            PaymentChannelResult result = channelRouter.route(order.getChannel()).pay(channelReq);

            if (result.isSuccess()) {
                order.setStatus(PaymentStatus.SUCCESS);
                order.setChannelTradeNo(result.getChannelTradeNo());
                order.setPaidAt(LocalDateTime.now());
                log.info("支付成功: outTradeNo={}, channel={}, channelTradeNo={}",
                        order.getOutTradeNo(), order.getChannel(), result.getChannelTradeNo());
            } else {
                order.setStatus(PaymentStatus.FAILED);
                order.setFailReason(result.getFailReason());
                log.warn("支付失败: outTradeNo={}, 原因={}", order.getOutTradeNo(), result.getFailReason());
            }
        } catch (Exception e) {
            order.setStatus(PaymentStatus.FAILED);
            order.setFailReason("渠道异常: " + e.getMessage());
            log.error("支付渠道异常: outTradeNo={}", order.getOutTradeNo(), e);
        } finally {
            order.setUpdatedAt(LocalDateTime.now());
            paymentOrderRepository.save(order);
        }

        return PaymentResult.from(order);
    }

    // ── 关闭订单 ──────────────────────────────────────────────────────────────

    public PaymentResult closePayment(String outTradeNo) {
        PaymentOrder order = getOrder(outTradeNo);

        if (order.getStatus() != PaymentStatus.PENDING && order.getStatus() != PaymentStatus.PAYING) {
            throw new PaymentException(
                    String.format("只能关闭待支付或支付中的订单，当前状态: %s", order.getStatus()));
        }

        order.setStatus(PaymentStatus.CLOSED);
        order.setClosedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        paymentOrderRepository.save(order);

        log.info("订单已关闭: outTradeNo={}", outTradeNo);
        return PaymentResult.from(order);
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    public PaymentResult queryPayment(String outTradeNo) {
        return PaymentResult.from(getOrder(outTradeNo));
    }

    public PaymentOrder getOrder(String outTradeNo) {
        return paymentOrderRepository.findByOutTradeNo(outTradeNo)
                .orElseThrow(() -> new PaymentOrderNotFoundException(outTradeNo));
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private PaymentOrder buildOrder(CreatePaymentRequest req) {
        PaymentOrder order = new PaymentOrder();
        order.setId(UUID.randomUUID().toString());
        order.setOutTradeNo(req.getOutTradeNo());
        order.setUserId(req.getUserId());
        order.setAmount(req.getAmount());
        order.setRefundedAmount(BigDecimal.ZERO);
        order.setChannel(req.getChannel());
        order.setSubject(req.getSubject());
        order.setStatus(PaymentStatus.PENDING);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private PaymentChannelRequest buildChannelRequest(PaymentOrder order) {
        PaymentChannelRequest req = new PaymentChannelRequest();
        req.setOutTradeNo(order.getOutTradeNo());
        req.setUserId(order.getUserId());
        req.setAmount(order.getAmount());
        req.setSubject(order.getSubject());
        return req;
    }

    private void validateRequest(CreatePaymentRequest req) {
        if (req.getOutTradeNo() == null || req.getOutTradeNo().isBlank()) {
            throw new PaymentException("outTradeNo 不能为空");
        }
        if (req.getUserId() == null || req.getUserId().isBlank()) {
            throw new PaymentException("userId 不能为空");
        }
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentException("支付金额必须大于 0");
        }
        if (req.getAmount().scale() > 2) {
            throw new PaymentException("金额最多保留两位小数");
        }
        if (req.getChannel() == null) {
            throw new PaymentException("支付渠道不能为空");
        }
        if (req.getSubject() == null || req.getSubject().isBlank()) {
            throw new PaymentException("商品描述不能为空");
        }
    }
}
