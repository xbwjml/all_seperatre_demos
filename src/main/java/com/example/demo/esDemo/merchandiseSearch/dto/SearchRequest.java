package com.example.demo.esDemo.merchandiseSearch.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 商品搜索请求。
 *
 * <pre>
 * {
 *   "keyword": "iPhone",
 *   "brands":  ["苹果","华为"],
 *   "categoryId": "mobile",
 *   "minPrice": 1000, "maxPrice": 10000,
 *   "attrs": { "颜色": ["红色"], "内存": ["128GB","256GB"] },
 *   "sort":  "composite",          # composite(综合) / sales / price_asc / price_desc / newest
 *   "page":  1,
 *   "size":  10,
 *   "withAggs": true                # 是否返回筛选项聚合
 * }
 * </pre>
 */
@Data
public class SearchRequest {

    private String keyword;

    private List<String> brands;

    private String categoryId;

    private BigDecimal minPrice;

    private BigDecimal maxPrice;

    /** 规格属性筛选：key=属性名，value=可选值列表（OR 关系）。 */
    private Map<String, List<String>> attrs;

    /** 排序方式：composite / sales / price_asc / price_desc / newest / rating。 */
    private String sort = "composite";

    private Integer page = 1;

    private Integer size = 10;

    /** 是否返回左侧筛选栏（品牌 / 价格区间 / 属性）聚合。 */
    private Boolean withAggs = Boolean.TRUE;

    /** 是否只查在售且有库存的商品。 */
    private Boolean onlyAvailable = Boolean.TRUE;
}
