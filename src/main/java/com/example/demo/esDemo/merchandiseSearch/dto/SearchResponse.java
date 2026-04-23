package com.example.demo.esDemo.merchandiseSearch.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 商品搜索响应。
 *
 * <p>{@code items} 是商品卡片数据（包含高亮片段），
 * {@code aggs} 是左侧筛选栏（品牌/价格区间/属性）。
 */
@Data
public class SearchResponse {

    private long total;

    private long tookMs;

    private int page;

    private int size;

    /** 命中商品列表（已合并高亮片段到 _source）。 */
    private List<Map<String, Object>> items;

    /** 聚合结果：key=facet 名，value=桶列表。 */
    private Map<String, List<FacetBucket>> aggs;
}
