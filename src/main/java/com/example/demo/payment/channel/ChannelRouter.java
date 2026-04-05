package com.example.demo.payment.channel;

import com.example.demo.payment.channel.mock.AlipayGateway;
import com.example.demo.payment.channel.mock.BalanceGateway;
import com.example.demo.payment.channel.mock.WechatPayGateway;
import com.example.demo.payment.domain.PaymentChannel;
import com.example.demo.payment.exception.PaymentException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 渠道路由器：根据 PaymentChannel 枚举分发到对应的网关实现。
 * 新增渠道只需实现 PaymentChannelGateway 并在此注册即可。
 */
@Component
public class ChannelRouter {

    private final Map<PaymentChannel, PaymentChannelGateway> gateways;

    public ChannelRouter(AlipayGateway alipay,
                         WechatPayGateway wechat,
                         BalanceGateway balance) {
        this.gateways = Map.of(
                PaymentChannel.ALIPAY,      alipay,
                PaymentChannel.WECHAT_PAY,  wechat,
                PaymentChannel.BALANCE,     balance
        );
    }

    public PaymentChannelGateway route(PaymentChannel channel) {
        PaymentChannelGateway gateway = gateways.get(channel);
        if (gateway == null) {
            throw new PaymentException("不支持的支付渠道: " + channel);
        }
        return gateway;
    }
}
