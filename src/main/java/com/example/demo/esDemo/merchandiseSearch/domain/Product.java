package com.example.demo.esDemo.merchandiseSearch.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品领域模型。直接作为 ES 文档 _source。
 *
 * <p>字段设计要点（与 {@code QueryDslBuilder#buildMapping()} 保持一致）：
 * <ul>
 *   <li>{@code title}：text，支持分词检索；同时通过 fields.keyword 支持精确匹配 / 排序。</li>
 *   <li>{@code brand / categoryId / tags}：keyword，用于过滤和聚合。</li>
 *   <li>{@code attrs}：nested，支持"颜色+内存"多维属性过滤。</li>
 *   <li>{@code sales / rating / price}：数值，用于排序和函数评分。</li>
 *   <li>{@code suggest}：completion，用于搜索下拉提示。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    private String productId;

    private String title;

    private String subTitle;

    private String brand;

    private String categoryId;

    private String categoryPath;

    private BigDecimal price;

    private Integer sales;

    private Integer sales30d;

    private Float rating;

    private Integer stock;

    private Boolean isOnSale;

    private String shopId;

    private List<String> tags;

    private List<ProductAttr> attrs;

    /** completion suggest 所需字段（只在写入时构造）。 */
    private List<String> suggest;

    private LocalDateTime createdAt;
}
