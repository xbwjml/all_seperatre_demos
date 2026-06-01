package com.example.demo.ShardingSphereCases.orderCase.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * 订单物理表分片算法 (按 user_id):
 * <pre>
 *   table_index = user_id % TBL_COUNT_PER_DB
 * </pre>
 * 与 GeneSnowflakeKeyGenerator.tableIndex(orderId) 保持一致。
 */
public class OrderTblShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private int tableCountPerDb;

    @Override
    public void init(Properties props) {
        this.tableCountPerDb = Integer.parseInt(props.getProperty("table-count-per-db", "4"));
        if (tableCountPerDb <= 0) {
            throw new IllegalArgumentException("table-count-per-db must be positive");
        }
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Long> shardingValue) {
        long userId = shardingValue.getValue();
        int tableIndex = (int) Math.floorMod(userId, tableCountPerDb);
        String logicTable = shardingValue.getLogicTableName();
        String suffix = logicTable + "_" + tableIndex;
        return availableTargetNames.stream()
                .filter(name -> name.equalsIgnoreCase(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No actual table matched for " + suffix + " in " + availableTargetNames));
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Long> shardingValue) {
        return availableTargetNames;
    }

    @Override
    public String getType() {
        return "ORDER_TBL";
    }
}
