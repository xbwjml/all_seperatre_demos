package com.example.demo.ShardingSphereCases.orderCase.mapper;

import com.example.demo.ShardingSphereCases.orderCase.domain.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单主表 MyBatis Mapper。
 *
 * <p>所有 SQL 都通过 ShardingSphere 的逻辑表 {@code t_order} 操作,
 * 必须包含 user_id 作为分片键, 否则会触发全分片广播。</p>
 */
@Mapper
public interface OrderMapper {

    int insert(Order order);

    /** 用户维度精确查询(分片键命中, 单分片单表)。返回 null 表示未命中。 */
    Order selectByUserAndOrderId(@Param("userId") long userId,
                                 @Param("orderId") long orderId);

    /**
     * 仅凭 order_id 查询(WHERE 不含分片键 user_id)。
     *
     * <p>standard 策略下会广播到所有分片并归并; 因 order_id 上有唯一键,
     * 命中至多一行。用于"只持有 order_id"的场景。返回 null 表示未命中。</p>
     */
    Order selectByOrderId(@Param("orderId") long orderId);

    /** 用户订单列表, 基于 order_id 游标降序分页。 */
    List<Order> listByUser(@Param("userId") long userId,
                           @Param("lastOrderId") long lastOrderIdExclusive,
                           @Param("size") int size);

    /**
     * 状态更新 + 乐观锁 + 状态机校验, 返回受影响行数(0 表示版本冲突)。
     * payTime 仅在转入"已支付"时传入。
     */
    int updateStatus(@Param("userId") long userId,
                     @Param("orderId") long orderId,
                     @Param("expectedFromStatus") byte expectedFromStatus,
                     @Param("targetStatus") byte targetStatus,
                     @Param("expectedVersion") int expectedVersion,
                     @Param("payTime") LocalDateTime payTime);
}
