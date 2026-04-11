package com.example.demo.localMsgTable.payStock.channel;

import com.example.demo.localMsgTable.payStock.enums.PayChannel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "demo.pay-stock.enabled", havingValue = "true")
public class PayChannelRouter {

    private final Map<PayChannel, PayChannelGateway> gatewayMap;

    public PayChannelRouter(List<PayChannelGateway> gateways) {
        this.gatewayMap = gateways.stream()
                .collect(Collectors.toMap(PayChannelGateway::channel, Function.identity()));
    }

    public PayChannelGateway route(PayChannel channel) {
        PayChannelGateway gateway = gatewayMap.get(channel);
        if (gateway == null) {
            throw new IllegalArgumentException("Unsupported payment channel: " + channel);
        }
        return gateway;
    }
}
