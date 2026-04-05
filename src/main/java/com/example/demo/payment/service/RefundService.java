package com.example.demo.payment.service;

import com.example.demo.payment.channel.ChannelRouter;
import com.example.demo.payment.channel.PaymentChannelResult;
import com.example.demo.payment.channel.RefundChannelRequest;
import com.example.demo.payment.domain.PaymentOrder;
import com.example.demo.payment.domain.PaymentStatus;
import com.example.demo.payment.domain.Refund;
import com.example.demo.payment.domain.RefundStatus;
import com.example.demo.payment.dto.CreateRefundRequest;
import com.example.demo.payment.dto.RefundResult;
import com.example.demo.payment.exception.PaymentException;
import com.example.demo.payment.exception.RefundException;
import com.example.demo.payment.repository.PaymentOrderRepository;
import com.example.demo.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 退款核心服务。
 *
 * <p>关键设计：
 * <ol>
 *   <li><b>幂等性</b>：outRefundNo 为唯一键，重复请求直接返回已有结果</li>
 *   <li><b>部分退款</b>：可多次退款，累计退款金额不超过支付金额</li>
 *   <li><b>全额退款检测</b>：退款后若 refundedAmount = amount，将支付单置为 REFUNDED</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundRepository refundRepository;
    private final ChannelRouter channelRouter;
    private final PaymentService paymentService;

    // ── 发起退款 ──────────────────────────────────────────────────────────────

    public RefundResult refund(CreateRefundRequest request) {
        validateRequest(request);

        // 1. 查询原支付订单（必须存在且已支付成功）
        PaymentOrder payment = paymentService.getOrder(request.getOutTradeNo());
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new RefundException(
                    String.format("只能对支付成功的订单发起退款，当前状态: %s", payment.getStatus()));
        }

        // 2. 校验退款金额不超过可退金额
        BigDecimal refundable = payment.getRefundableAmount();
        if (request.getRefundAmount().compareTo(refundable) > 0) {
            throw new RefundException(
                    String.format("退款金额超出可退金额，可退: %s，申请退款: %s",
                            refundable.toPlainString(), request.getRefundAmount().toPlainString()));
        }

        // 3. 幂等：已存在则直接返回
        var existing = refundRepository.findByOutRefundNo(request.getOutRefundNo());
        if (existing.isPresent()) {
            log.info("重复退款请求，outRefundNo={}", request.getOutRefundNo());
            return RefundResult.from(existing.get());
        }

        // 4. 创建退款单
        Refund refund = buildRefund(request, payment);
        refundRepository.saveIfAbsent(refund);

        // 5. 调用渠道退款
        refund.setStatus(RefundStatus.PROCESSING);
        try {
            PaymentChannelResult result = channelRouter.route(payment.getChannel())
                    .refund(buildChannelRefundRequest(refund));

            if (result.isSuccess()) {
                refund.setStatus(RefundStatus.SUCCESS);
                refund.setChannelRefundNo(result.getChannelRefundNo());
                refund.setRefundedAt(LocalDateTime.now());

                // 累加已退款金额，判断是否全额退款
                payment.setRefundedAmount(payment.getRefundedAmount().add(request.getRefundAmount()));
                boolean fullyRefunded = payment.getRefundedAmount().compareTo(payment.getAmount()) >= 0;
                payment.setStatus(fullyRefunded ? PaymentStatus.REFUNDED : PaymentStatus.SUCCESS);
                payment.setUpdatedAt(LocalDateTime.now());
                paymentOrderRepository.save(payment);

                log.info("退款成功: outRefundNo={}, outTradeNo={}, amount={}, 支付单状态={}",
                        refund.getOutRefundNo(), payment.getOutTradeNo(),
                        request.getRefundAmount(), payment.getStatus());
            } else {
                refund.setStatus(RefundStatus.FAILED);
                refund.setFailReason(result.getFailReason());
                log.warn("退款失败: outRefundNo={}, 原因={}", refund.getOutRefundNo(), result.getFailReason());
            }
        } catch (Exception e) {
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailReason("渠道异常: " + e.getMessage());
            log.error("退款渠道异常: outRefundNo={}", refund.getOutRefundNo(), e);
        } finally {
            refund.setUpdatedAt(LocalDateTime.now());
            refundRepository.save(refund);
        }

        return RefundResult.from(refund);
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    public RefundResult queryRefund(String outRefundNo) {
        return RefundResult.from(
                refundRepository.findByOutRefundNo(outRefundNo)
                        .orElseThrow(() -> new PaymentException("退款单不存在: " + outRefundNo))
        );
    }

    public List<RefundResult> queryRefundsByTradeNo(String outTradeNo) {
        return refundRepository.findByOutTradeNo(outTradeNo)
                .stream().map(RefundResult::from).toList();
    }

    // ── 私有方法 ──────────────────────────────────────────────────────────────

    private Refund buildRefund(CreateRefundRequest req, PaymentOrder payment) {
        Refund refund = new Refund();
        refund.setId(UUID.randomUUID().toString());
        refund.setOutRefundNo(req.getOutRefundNo());
        refund.setOutTradeNo(req.getOutTradeNo());
        refund.setChannelTradeNo(payment.getChannelTradeNo());
        refund.setRefundAmount(req.getRefundAmount());
        refund.setReason(req.getReason());
        refund.setStatus(RefundStatus.PENDING);
        refund.setCreatedAt(LocalDateTime.now());
        refund.setUpdatedAt(LocalDateTime.now());
        return refund;
    }

    private RefundChannelRequest buildChannelRefundRequest(Refund refund) {
        RefundChannelRequest req = new RefundChannelRequest();
        req.setOutRefundNo(refund.getOutRefundNo());
        req.setOutTradeNo(refund.getOutTradeNo());
        req.setChannelTradeNo(refund.getChannelTradeNo());
        req.setRefundAmount(refund.getRefundAmount());
        req.setReason(refund.getReason());
        return req;
    }

    private void validateRequest(CreateRefundRequest req) {
        if (req.getOutRefundNo() == null || req.getOutRefundNo().isBlank()) {
            throw new RefundException("outRefundNo 不能为空");
        }
        if (req.getOutTradeNo() == null || req.getOutTradeNo().isBlank()) {
            throw new RefundException("outTradeNo 不能为空");
        }
        if (req.getRefundAmount() == null || req.getRefundAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new RefundException("退款金额必须大于 0");
        }
        if (req.getRefundAmount().scale() > 2) {
            throw new RefundException("退款金额最多保留两位小数");
        }
    }
}
