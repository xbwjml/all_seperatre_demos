package com.example.demo.ShardingSphereCases.orderCase.algorithm;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * 订单库分片算法 (按 user_id):
 * <pre>
 *   db_index = (user_id % TOTAL) / TBL_COUNT_PER_DB
 * </pre>
 * 与 GeneSnowflakeKeyGenerator.dbIndex(orderId) 保持一致。
 *
 * <p>YAML 配置:</p>
 * <pre>
 * shardingAlgorithms:
 *   order_db_algo:
 *     type: CLASS_BASED
 *     props:
 *       strategy: STANDARD
 *       algorithmClassName: com.example.demo.ShardingSphereCases.orderCase.algorithm.OrderDbShardingAlgorithm
 *       db-count: 2
 *       table-count-per-db: 4
 * </pre>
 */
public class OrderDbShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private int dbCount;
    private int tableCountPerDb;
    private int total;

    @Override
    public void init(Properties props) {
        this.dbCount = Integer.parseInt(props.getProperty("db-count", "2"));
        this.tableCountPerDb = Integer.parseInt(props.getProperty("table-count-per-db", "4"));
        this.total = dbCount * tableCountPerDb;
        if (total <= 0) {
            throw new IllegalArgumentException("db-count * table-count-per-db must be positive");
        }
    }

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Long> shardingValue) {
        long userId = shardingValue.getValue();
        int dbIndex = (int) ((Math.floorMod(userId, total)) / tableCountPerDb);
        String suffix = "ds" + dbIndex;
        return availableTargetNames.stream()
                .filter(name -> name.equalsIgnoreCase(suffix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No data source matched for " + suffix + " in " + availableTargetNames));
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Long> shardingValue) {
        return availableTargetNames;
    }

    @Override
    public String getType() {
        return "ORDER_DB";
    }
}
