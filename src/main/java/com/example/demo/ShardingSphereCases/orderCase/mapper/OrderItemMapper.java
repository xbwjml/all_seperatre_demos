package com.example.demo.ShardingSphereCases.orderCase.mapper;

import com.example.demo.ShardingSphereCases.orderCase.domain.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单子表 MyBatis Mapper。
 *
 * <p>{@code t_order_item} 与 {@code t_order} 配置为绑定表(binding-tables),
 * 同 user_id 一定路由到相同物理库表, 保证主子表 JOIN 不发生跨节点广播。</p>
 */
@Mapper
public interface OrderItemMapper {

    /** 批量插入子项, 与父订单同事务、同分片。 */
    int batchInsert(@Param("items") List<OrderItem> items);

    /** 根据用户 + 订单 ID 拉取所有子项。 */
    List<OrderItem> selectByUserAndOrderId(@Param("userId") long userId,
                                           @Param("orderId") long orderId);
}
