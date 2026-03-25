package com.example.demo.beanPostProcessor.cases.case2_rpc;

/**
 * 用户服务 RPC 接口（对应 user-center 微服务提供的能力）。
 *
 * <p>消费方只依赖此接口，无需关心网络通信实现。
 * 代理对象由 {@link RpcReferencePostProcessor} 在容器启动时自动注入。</p>
 */
public interface UserRpcService {

    String getUserName(Long userId);

    boolean isVip(Long userId);

    String getUserPhone(Long userId);
}
