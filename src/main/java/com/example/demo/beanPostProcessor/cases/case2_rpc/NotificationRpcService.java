package com.example.demo.beanPostProcessor.cases.case2_rpc;

/**
 * 通知服务 RPC 接口（对应 notification-center 微服务提供的能力）。
 */
public interface NotificationRpcService {

    void sendSms(String phone, String content);

    void sendPush(Long userId, String title, String body);
}
