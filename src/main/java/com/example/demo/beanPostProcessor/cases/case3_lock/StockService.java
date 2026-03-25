package com.example.demo.beanPostProcessor.cases.case3_lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 库存服务，演示 {@code @DistributedLock} 的使用。
 *
 * <p>高并发场景下，扣减库存和创建订单必须串行化，
 * 通过注解声明分布式锁，无需手动编写 Redis SETNX / Lua 脚本。</p>
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /**
     * 扣减库存。
     * key = 'stock:deduct:{skuId}'，保证同一商品的并发扣减排队执行。
     */
    @DistributedLock(key = "'stock:deduct:' + #skuId", expire = 10)
    public boolean deductStock(String skuId, int quantity) {
        log.info("[StockService] 扣减库存: skuId={}, quantity={}", skuId, quantity);
        // 真实业务：SELECT FOR UPDATE → 比较库存 → UPDATE stock - quantity
        return true;
    }

    /**
     * 创建订单。
     * key = 'order:create:{userId}'，防止用户重复提交订单。
     */
    @DistributedLock(key = "'order:create:' + #userId", expire = 5)
    public String createOrder(Long userId, String skuId, int quantity) {
        log.info("[StockService] 创建订单: userId={}, skuId={}, quantity={}", userId, skuId, quantity);
        return "ORDER_" + System.currentTimeMillis();
    }
}
