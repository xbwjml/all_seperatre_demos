package com.example.demo.ShardingSphereCases.orderCase.id;

import com.example.demo.ShardingSphereCases.orderCase.config.OrderShardingProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基因雪花 ID 生成器:
 * <pre>
 *   1 bit 符号 + 41 bit 时间戳 + 4 bit workerId + 8 bit 序列号 + 10 bit 基因 = 64 bit
 * </pre>
 * 基因 = user_id &amp; 0x3FF, 与分片算法对齐, 用于"凭 order_id 反向路由分片"。
 *
 * <p>注意: 不通过 ShardingSphere KeyGenerateAlgorithm SPI 生成,
 * 因为 SPI 接口 generateKey() 拿不到 user_id 上下文。
 * 由业务层显式调用 nextId(userId) 生成。</p>
 */
@Component
@ConditionalOnProperty(prefix = "demo.order-sharding", name = "enabled", havingValue = "true")
public class GeneSnowflakeKeyGenerator {

    private static final long EPOCH        = 1735660800000L; // 2025-01-01 00:00:00 UTC
    private static final int  GENE_BITS    = 10;
    private static final int  SEQ_BITS     = 8;
    private static final int  WORKER_BITS  = 4;
    private static final long MAX_SEQ      = (1L << SEQ_BITS)    - 1; // 255
    private static final long MAX_WORKER   = (1L << WORKER_BITS) - 1; // 15
    private static final long GENE_MASK    = (1L << GENE_BITS)   - 1; // 0x3FF

    private final long workerId;
    private final OrderShardingProperties props;

    private long lastTs   = -1L;
    private long sequence = 0L;

    public GeneSnowflakeKeyGenerator(OrderShardingProperties props) {
        this.props = props;
        long wid = props.getWorkerId();
        if (wid < 0 || wid > MAX_WORKER) {
            throw new IllegalArgumentException(
                    "workerId must be in [0, " + MAX_WORKER + "], got " + wid);
        }
        this.workerId = wid;
    }

    @PostConstruct
    public void init() {
        int total = props.getTotalShards();
        int geneCapacity = 1 << GENE_BITS; // 1024

        // 约束 1: 总分片数不能超过基因位容量, 否则基因放不下分片下标。
        if (total > geneCapacity) {
            throw new IllegalStateException(
                    "Total shards exceed gene capacity (" + geneCapacity + "): "
                            + props.getDbCount() + " x " + props.getTableCountPerDb()
                            + " = " + total);
        }
        // 约束 2: 基因 = userId & 0x3FF = userId % 1024。仅当 total 整除 1024
        // (即 total 为 2 的幂且 <= 1024) 时, (userId % 1024) % total == userId % total 恒成立,
        // 基因反推路由才能与写入路由一致。total 为 2 的幂时, dbCount 与 tableCountPerDb 也必然是 2 的幂。
        if (total <= 0 || (total & (total - 1)) != 0) {
            throw new IllegalStateException(
                    "Total shards must be a power of two (so gene reverse-routing stays consistent), got: "
                            + props.getDbCount() + " x " + props.getTableCountPerDb()
                            + " = " + total);
        }
    }

    public synchronized long nextId(long userId) {
        long now = System.currentTimeMillis();

        // 时钟回拨保护: 小幅(<=5ms)等待, 大幅直接抛异常
        if (now < lastTs) {
            long offset = lastTs - now;
            if (offset <= 5) {
                try {
                    Thread.sleep(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ID generation interrupted", e);
                }
                now = System.currentTimeMillis();
            }
            if (now < lastTs) {
                throw new IllegalStateException(
                        "Clock moved backwards by " + (lastTs - now) + "ms, refuse to generate id");
            }
        }

        if (now == lastTs) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                while (now <= lastTs) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0;
        }
        lastTs = now;

        long gene = userId & GENE_MASK;
        return ((now - EPOCH) << (WORKER_BITS + SEQ_BITS + GENE_BITS))
                | (workerId       << (SEQ_BITS + GENE_BITS))
                | (sequence       <<  GENE_BITS)
                |  gene;
    }

    /** 凭 order_id 反推 user_id 所在 db 下标 (基于当前模块配置)。 */
    public int dbIndexOf(long orderId) {
        int total = props.getTotalShards();
        return (int) ((Math.floorMod(orderId & GENE_MASK, total)) / props.getTableCountPerDb());
    }

    /** 凭 order_id 反推物理表下标。 */
    public int tableIndexOf(long orderId) {
        return (int) Math.floorMod(orderId & GENE_MASK, props.getTableCountPerDb());
    }

    /**
     * 由 order_id 反推基因值(低 10 位)。
     *
     * <p>它与原 user_id 的 {@code % total} 结果一致, 因此可用来<b>计算分片落点</b>
     * (见 {@link #dbIndexOf}/{@link #tableIndexOf}); 但它<b>不是</b>真实 user_id,
     * 绝不能当作 {@code WHERE user_id = ?} 的过滤值, 否则查不到真实行。</p>
     *
     * <p>standard 分片策略下, 仅凭 order_id 查询应直接以 {@code WHERE order_id = ?}
     * 广播归并(order_id 唯一), 而非用本值伪造 user_id 过滤。</p>
     */
    public long geneOf(long orderId) {
        return orderId & GENE_MASK;
    }
}
