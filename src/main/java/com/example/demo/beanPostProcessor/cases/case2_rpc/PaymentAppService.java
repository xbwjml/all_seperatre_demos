package com.example.demo.beanPostProcessor.cases.case2_rpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付应用服务（RPC 消费方）。
 *
 * <p>通过 {@code @RpcReference} 注入远程服务代理，调用方式与本地 Bean 完全相同，
 * 无需感知 Netty 连接、序列化、负载均衡等细节。</p>
 */
@Service
public class PaymentAppService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAppService.class);

    @RpcReference(version = "2.0.0", group = "user-center", timeout = 1000)
    private UserRpcService userRpcService;

    @RpcReference(version = "1.0.0", group = "notification", timeout = 2000)
    private NotificationRpcService notificationRpcService;

    /**
     * 支付成功后发送通知。
     */
    public void onPaySuccess(Long orderId, Long userId, String amount) {
        String userName = userRpcService.getUserName(userId);
        String phone    = userRpcService.getUserPhone(userId);
        boolean isVip   = userRpcService.isVip(userId);

        String content = String.format("尊敬的%s，您的订单 %d 已支付成功，金额 %s 元%s",
                userName, orderId, amount, isVip ? "（VIP专属回执）" : "");

        notificationRpcService.sendSms(phone, content);
        notificationRpcService.sendPush(userId, "支付成功", content);

        log.info("[PaymentAppService] 支付通知发送完成：orderId={}, userId={}, vip={}",
                orderId, userId, isVip);
    }
}
